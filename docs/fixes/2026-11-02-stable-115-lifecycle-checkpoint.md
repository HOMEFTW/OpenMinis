> 阶段交接归档：以下待办和测试数量仅描述当时状态。最终实现及验证以 [稳定版同步记录](2026-11-02-stable-115-sync.md) 为准。

# Android 1.15 build37 生命周期接入交接

参照：`D:/Code/.tools/openminis-stable-115-sync-20261102/upstream`，`6eda5523a9c14fa8edc4fd9fb72e643fe47f23be`。

本代理未编辑 `ChatViewModel.kt`、`config/**`、`SessionUnreadStore.kt`、Manifest、版本或网络代码；没有运行 Gradle、commit、push。资源扩展仅为后续明确要求的 OAuth 服务直接依赖。

## 已实现及文件

- `agent/jobs/UserQuestionTool.kt`：沿用 fork Broker，增加可恢复但无协程的卡片、持久化前置回调、持久化墙钟期限；分步回答、草稿、旧实例校验和已有关联别名保留。
- `agent/jobs/UserQuestionRecordStore.kt`：每个实例独立 JSON 记录；同步写入 SharedPreferences；恢复时转换墙钟期限到本次启动的 elapsedRealtime；校验字段、答案与封闭选项；坏记录独立跳过。终态答案必须等 DB 提交后确认移除。
- `agent/jobs/UserQuestionRegistry.kt`：冷启动恢复、进程内与恢复 Broker 的统一路由、终态 `recoveredResults`、`acknowledge` 和 `cancelSession`。
- `agent/jobs/UserQuestionRecovery.kt`：通过 DB 适配器补回 assistant/tool_result，避免重放实际工具；补齐同 batch 被重启打断的兄弟调用；已存在结果去重；DB 写完后再移除记录。删除会话不写入；旧记录缺 assistant 快照时保留为普通用户文本。
- `service/UserQuestionNotifications.kt`：接收器可冷启动恢复记录并回答；恢复前一进程的通知所有权；进程生命周期观察，避免 FGS 停止使提问失去通知；独立 Activity 可见状态避免 overlay 初始 `true` 导致后台冷启动误删通知；到期主动清理。
- `ui/chat/UserQuestion.kt`：增加 `expiresAtWallMs`，其余卡片契约保留。
- `agent/jobs/AgentJobRegistry.kt`：原子预留按每个父会话各 3 个；队列容量按父会话各 10 个；一个父会话占满时不挡另一父会话的可用槽。保留无参旧属性供尚未接入的调用点编译，但运行准入必须切换到带父会话参数的 API。
- `service/ScheduledSessionState.kt`：定时等待行模型、最早等待选择、计数文本、普通/完成/等待/无期限与已到期的时钟策略。
- `service/SessionActivityTracker.kt`：初始化提问恢复与 OAuth keep-alive；`setScheduledSessions` / `scheduledSessions` / `scheduledWaits` / `isProcessing`；通知 Stop 先取消定时器，再关闭包括恢复 Broker 在内的提问和活动循环。
- `service/AgentForegroundService.kt`、`AgentNotificationContent.kt`：独立 `SCHEDULED_WAIT` 阶段、系统倒计时、等待期间 Stop 与服务保活、滑掉任务时保留 scheduled hold。普通通知与 Android 16 promoted 分支共享时钟，避免负倒计时。
- `service/OAuthWaitKeepAlive.kt`、`OAuthWaitService.kt`：按本次补充要求加入 upstream 两个文件。流程按 id 幂等计数，最长 5 分钟；dataSync 前台服务先履行 FGS 启动契约，空流/到期/任务移除/系统超时后停止，启动失败不阻断登录。
- `res/{values,values-zh,values-zh-rTW}/oauth_wait_service.xml`：只增加 OAuth 服务所需 3 个字符串；来自稳定源码。

## 主代理需接入 ChatViewModel

以下行号是修改前定位参考，按符号寻找，不按行号整段覆盖。

1. **assistant 快照（原约 10580 行）**

   在工具执行前的 live preview 块，已有 `livePreviewParts` 和 `liveMeta`。将该批次完整 assistant parts JSON 存入一个 VM 字段，例如 `pendingQuestionAssistantPartsJson`，包含本次提问的 toolUse 以及同批次其他调用。优先用 `serializeAssistantParts(livePreviewParts, liveMeta, turnDeepSeekReplay, turnAnthropicReplay)` 保留供应商回放信息；不要只保存问题文字。

   `publishUserQuestion`（原约 1386 行）：

   ```kotlin
   UserQuestionRegistry.update(activeSessionId, request, userQuestions, pendingQuestionAssistantPartsJson)
   ```

   首次提问必须已经 `ensureSession()`，不要持久化 draft 会话。VM 字段请放在创建 Broker 之前；批次完成后清空快照。

2. **恢复卡片状态与操作（原约 1383–1397 行）**

   当前 `pendingUserQuestion = userQuestions.pending` 看不到 Registry 恢复的另一个 Broker。合并 Registry 会话条目与本地流，或直接观察 `UserQuestionRegistry.pending` 并映射到 `activeSessionId`。会话切换/从 draft 变真实 id 时也要重新取值。

   ```kotlin
   UserQuestionRegistry.select(activeSessionId, request.instanceId, questionId, value, asOther)
   UserQuestionRegistry.submitSelected(activeSessionId, request.instanceId, request.currentQuestion!!.id)
   UserQuestionRegistry.dismiss(activeSessionId, request.instanceId)
   ```

   上述路由同样适用于正常运行，仍校验 instanceId/current question，不需要复制或替换 Broker。

3. **持久化恢复与继续运行**

   为 `UserQuestionTranscript` 提供 ChatRepository 适配器：

   ```kotlin
   override suspend fun sessionExists(id: String) = chatRepository.getSession(id) != null
   override suspend fun rows(id: String) = chatRepository.loadMessages(id).map { it.partsJson }
   override suspend fun append(id: String, role: String, parts: String) {
       chatRepository.appendMessage(id, role, parts)
   }
   ```

   在会话加载、读取消息前，以及 `UserQuestionRegistry.recoveredResults` 对该会话出现终态时，调用 `UserQuestionRecovery.deliver(sessionId, adapter)`。它会串行去重，并先写 DB、后确认记录；随后重新加载 UI 与 agentHistory，返回 true 时仅恢复一次，false 时不唤醒（Stop、关闭、删除等）。加载失败不要先移除记录。

   **只监听当前可见 VM 不够**：通知可以单独冷启动应用，后台没有该会话 VM。需在应用协调层或初始化一次的全会话恢复监听中，对 `recoveredResults` 的 sessionId 使用现有 ChatViewModelStore/headless 获取 VM，再在 DB 恢复完成后 reload/resume。不要先 resume 读取未写完的历史，也不要靠重发工具重建答案。遇到用户新消息时先消化恢复结果，恢复布尔值不应额外启动与用户消息竞争的运行。

4. **正常结果确认（原 `persistToolResultMessage` 约 13010 行）**

   正常 `chatRepository.appendMessage` 成功后，对写入的提问 toolUseId 调用：

   ```kotlin
   UserQuestionRegistry.acknowledge(activeSessionId, result.id)
   ```

   未确认的终态记录故意留在磁盘，避免工具完成到 DB 提交之间被杀进程丢答案。不要在 `ask()` 刚返回时提前确认。

5. **Stop 与新用户消息**

   `cancelStreamInternal`（原约 14247 行）除 `userQuestions.cancel()` 外，调用 `UserQuestionRegistry.cancelSession(activeSessionId)`，因为恢复 Broker 不是 VM 的局部 Broker。Stop 应写入取消结果且不自动唤醒。

   新用户消息替代一个未答卡片时，关闭 Registry 中当前实例，并先补齐工具结果；随后由新消息开始运行。不要遗留原卡片供旧通知继续回答。

6. **每父会话子代理准入**

   原约 1794、2131、11349 行的无参 `registry.canStartChildJob` 改为 `registry.canStartChildJob(parentSid)`（各调用点使用其真实父会话）。原约 11381、11391 的排队计数改为 `registry.runningChildJobCount(parentSid)`。

   范围外 `scheduled/ScheduledSessionDispatcher.kt` 原约 37 行：

   ```kotlin
   if (childMode != null && !AgentJobRegistry.canStartChildJob(childMode.sessionId)) return@withContext null
   ```

   `tryRegisterChild` 已是带锁的最终原子关口；不要把 `register()` 当作安全预留 API 绕过它。500 轮与 6 会话上限已在源码，无需再改。

## 定时任务 owner 接入需求与剩余差距

service 层消费数据的接口已就绪，但本代理没有权限编辑 `scheduled/**` 和 VM，以下是必要接入，尚未完成端到端对齐：

- 当前任务模型缺 `creatorSessionId`。只有目标为 AppendToSession 且目标等于创建会话时，才保持创建会话 active；新会话、其他会话、隐藏子会话、rerun、无创建者不能错误地保持本会话 active。ON_COMPLETION 只在上游仍存在且未完成时保持。
- 根据任务持久化、进行中 run 和 countdown lead 配置推导等待窗口，调用 `SessionActivityTracker.setScheduledSessions(waits, heldIds)`。等待窗口起点也需定时重算；不能只等 SharedPreferences 改变，否则日程进入窗口时不会激活。最后一次 fire 已禁用任务但正在交接/运行时，要保留该 run 的 hold。
- `ScheduledSessionWait(taskId, sessionId, label, nextRunWallMs, runCounter)` 中 on-completion 的期限为 null；普通日程提供真实下次运行墙钟；列表/状态展示需将 `activeSessions` 与 `scheduledSessions` 合并，或使用 `isProcessing`。
- 安装 `SessionActivityTracker.cancelScheduledByUser = { creators -> ... }`，禁用这些创建者的任务且保留历史，再刷新 held set。VM 的全量 Stop 也需调用相同逻辑；“只停当前工具”保留其它后台工作。
- 当前 Run now 仍只调用 runner，未消费待运行 slot。需在 manager 内原子记录该 slot：AFTER/ON_COMPLETION 一次性禁用；INTERVAL 消费一次计数并维护锚点；CALENDAR 记录 runNowSlotMs 避免当前 slot 再次自动执行。具体行为参照稳定 manager 的 `runNowTransition`，不要仅把“执行成功”当作“计数已正确”。本代理未写 scheduled 文件。
- 稳定版完整 userask 的无人值守 deferred 投递、无限等待/交互续期、前后题导航等没有整套替换进 fork；当前保留其既有工具名、确认选项与拒绝无人值守提问策略。

## OAuth Manifest 交给主代理

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<service
    android:name=".service.OAuthWaitService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

已有依赖 androidx.core 与 coroutines 可复用，不需引入新库。`OAuthWaitKeepAlive.install(context)` 已在现有 `SessionActivityTracker.init` 中安装，不需要为了它再修改 MinisApp。

## 验证

运行 `src/android/app/src/test/java/com/openminis/app/agent/jobs/standalone/Run-LifecycleTests.ps1`；直接使用已缓存 Kotlin 2.1/JUnit/coroutines 与现有 app/R 依赖字节码，重新编译本次核心生产源码并执行定向测试，不调用 Gradle、不访问网络。脚本在系统临时目录生成独立日志和适配器，不改写项目构建输出。

Android 时钟/日志使用窄适配器；OAuthService.start 用拒绝启动的适配器，OAuth 测试不安装真实 Android Context。覆盖的是提问恢复/幂等/过期/取消、每父会话子代理隔离和竞争、6 会话排队与取消、通知阶段/时钟、OAuth 流计数与超时、shell 登记前排队取消。Android FGS、通知操作 UI、真实 OAuth 浏览器回跳、真实 shell/Doze 仍需要统一构建和设备验证；本代理未声称通过。

最终独立定向结果：**45 tests，0 failures**，含“答案已保存但尚未写回时 Stop 胜出”的回归。日志：`C:/Users/homeftw/AppData/Local/Temp/openminis-lifecycle-31b2593c968a4212a48fcbd25267b319/tests.log`。`git diff --check` 通过；3 个 OAuth 资源在默认/简中/繁中均唯一且 XML 可解析；两个 OAuth 服务与 upstream 归一化源码一致。后续主代理接入 VM、schedule 或 Manifest 的结果不包含在本次独立验证声明中。

原 `SessionCommandRegistry` 在排队之前登记 Job，`FreshProcessShell.stop()` 把进程清理交给 worker，当前无需在本代理范围内重复替换；本次对它们做只读审查与定向回归。

本阶段交付后停止 agent/jobs、service、ui/chat/UserQuestion、OAuth 资源及其测试写入。VM 唯一 owner 为 Euclid，本清单由主代理协调；本代理转入 backup 范围。
