> 阶段交接归档：以下待办和测试数量仅描述当时状态。最终实现及验证以 [稳定版同步记录](2026-11-02-stable-115-sync.md) 为准。

# VM / provider 阶段检查点（待主代理 full10）

基准为 Android 1.15 build37、`6eda5523a9c14fa8edc4fd9fb72e643fe47f23be`，源码位于 `D:/Code/.tools/openminis-stable-115-sync-20261102/upstream`。未使用 upstream/main；没有运行 Gradle、提交、推送或修改版本。本检查点交付后暂停写入，VM / Store / MinisApp 后续写入由主代理协调。

## 已接入

- `ChatViewModel.kt`：DSML 请求重试、按本轮可见文字判空和组成员切换；拒绝停止工具与重试并保留 partial text；嵌套 JSON 仅修执行副本；标题提醒；定时工具循环豁免；四协议及原生 DeepSeek 用量合并、重试清空和严格存储扩展字段。保留 fork 原生 DeepSeek / GPT6 high / Fast / 图片预算、通信与定时插入隔离。
- `agent/jobs/UserQuestionTool.kt`、`UserQuestionRegistry.kt`、`UserQuestionRecordStore.kt`、`UserQuestionRecovery.kt`、`UserQuestionSettings.kt` 与 `ui/chat/UserQuestion.kt`、`UserQuestionCard.kt`：整个 assistant 批次及签名快照，先写 DB 后 acknowledge，再 reload/resume；冷启动恢复卡片与统一操作；后台无 VM 时由 Store 创建 headless VM；Stop 和新用户消息胜出；每父会话子任务准入。`user_ask` 是公开工具名，旧 request_user_input / ask_user / ask_question 继续可执行；嵌套 schema，1–4 题，旧字符串 JSON 与确认题兼容。
- 问答 deferred 立即返回、卡片保留一小时且不显示倒计时，真实回答写回原 deferred 结果并作为新的用户消息继续；0 分钟无限等待，打字、选择、翻题和点击续期，前后题导航；旧通知 instanceId 校验保留。设置界面为 `ui/settings/AgentToolsSettingsScreen.kt`，新增资源为 `res/values{,-zh}/stable115_question_strings.xml`。卡片没有内部滚动器。
- `ChatViewModelStore.kt` 安装全会话问答结果监听；`MinisApp.kt` 在 Tracker 初始化后安装它，并在已有 IO scope 仅调用一次 OrphanChildSweeper.sweep，记录成功/失败。
- VM 暴露 `scheduledHold: StateFlow<Boolean>`，缓存保护与 hasBackgroundWorkToStop 使用真实 held session 集；全量 Stop 调用 ScheduledSessionCoordinator.stopCreatedBy，当前工具 Stop 不停其他计划。
- `tools/BatchToolOrder.kt`：同一批 reader 在拿并发槽之前 join 前序 writer，其他工具继续并发。
- `QueuedCallbackReminder.kt`：只提醒已完成、仍排队且未提醒的程序化子代理 callback；提醒只进请求副本，重试保持相同文本。收到有效回复 chunk 后才记录已提醒。沿用 fork 的 runId 来源集合，不改 ChatModels 的队列模型。
- `ContextTrimPlanner.kt` 与 VM：请求级 L1 reasoning、L2/L3 输出/参数 offload、skeleton、摘要有机会后 L4 collapse，以及冻结 cutoff、窗口/模型/压缩 marker 变更失效。计量复用 fork 的图片预算和校准方法；仅修改请求副本。修改 reasoning 或参数时移除已失效的供应商回放元数据，原始历史与 DB 签名不变。
- `RetryBadge.kt` 由主代理导入；VM 已暴露 `retryLog: StateFlow<List<RetryRecord>>`，实际重试时追加、结束后清空。
- `StopLateGuard.kt`、`ThinkingRowSettle.kt`：generation 覆盖整个循环、气泡切换、shell 回调和流写入；结束后清 thinking / awaiting 状态，overlay 不覆盖终态工具状态。对 fork 嵌套定时循环增加暂停/恢复父 generation 的适配。VM 提供 `liveTurnToken()` 与 `stopTool(blockId)`；旧工具卡片只清自身，不停止新回复。
- `PromptAttachments.kt`：程序化发送/入队只用自己携带的附件，不取走 composer；submitPrompt 新增可选 ownAttachments，保持原调用兼容。
- `FolderOfferFormat.kt`、`SessionTitleRules.kt`：自动归组提示附带近期会话标题示例；沿用已有 DAO 的 sessionIdsInFolder / getSession，在本地适配 FolderMemberTitle，未改 DAO。标题监听数据库，并在自动标题写回前复核用户标题。
- `ToolDurationWire.kt`、`ToolResultPersistence.kt`：tool_result.snapshot.duration 以秒存储，以毫秒恢复，不伪造未知时长。
- VM 对两处正常 assistant 持久化结果给对应 ChatMessage 赋 tokenUsage / completedAt；加载用 entity.tokenUsage / createdAt，连续 assistant 合并取最后一个有效值。调用主代理新增的严格 ChatTokenUsage.parse / StoredUsageJson，不改模型字段或数据库 schema。
- 释放 streaming 会话前按当前轮行内错误、canResume、用户 Stop 分类通知；Stop 显式 markStreamInterrupted。旧 streamJob 的 finally 不释放替代它的新 run。
- OpenAI Responses content_filter 保留已流出的 partial text，Finished.stopReason 为 content_filter；补解析 incomplete 事件 usage。未修改主代理负责的 Chat Completions body 配对逻辑。

## 网络覆盖（此前交付，本轮未改 network）

四协议聊天及 fork 原生 DeepSeek 的实际 HTTP 客户端均通过 NetworkHttp.client / providerTag；factory 传 provider 配置。provider 内模型列表 API、语音 HTTP / WebSocket 等已接。保留端点证书、取消、签名、重试及慢模型 timeout；auth / quota / 更新器归主代理。本轮未写 network、config、provider 模型 enrich、ProviderRepository 或版本文件。

## 本地验证

- 运行 `src/android/app/src/test/java/com/openminis/app/agent/jobs/standalone/Run-LifecycleTests.ps1`，直接调用缓存 Kotlin/JUnit/coroutines，不经过 Gradle。最新结果 **69 tests, 0 failures**：原生命周期/排队/通知时钟/OAuth 核心，另含无限等待、续期、导航、deferred 重启去重与 Stop 竞争，以及批次依赖、callback、分级压缩、冻结前缀、签名副本、嵌套 generation、停止后的 overlay、附件归属、用户标题、时长 wire。
- 日志：`C:/Users/homeftw/AppData/Local/Temp/openminis-lifecycle-ed461f1e71644f59bfdba17c82302536/tests.log`。
- 独立 Kotlin 编译通过的是此前问答/usage 检查点（包括 VM / Store / OpenAI provider / 定时核心），主代理 full8 也未报告该检查点编译错误。后续新增批次尚待 full10，不能将此前编译结果算作当前整包通过。
- ResponsesIncompletePartialTest 保留 partial text 断言，更新 content_filter 拒绝终止断言，并验证 incomplete usage。独立 HTTP 测试因 Android Debug native API 缺失未跑通；交主代理的 Android JVM 测试环境。没有声称该测试已通过。
- 当前静态检查结果以最终交付回复为准；没有设备/真实供应商/通知点击/进程被杀/Doze 验证。

## 主代理 / Aquinas 仍需衔接

1. full10 对最新 VM 与主代理 Usage / Speech / Native / UI 变更进行统一编译和测试；检查本轮新增的 Stable115LoopCoreTest、UserQuestionStable115Test，以及 ResponsesIncompletePartialTest。
2. 通知服务接口由 Aquinas 独占，未直接修改：UserQuestionNotifications 应按 PendingUserQuestion.hasCountdown 隐藏无限等待和 deferred 的倒计时；无限等待不要对 Notification.setTimeoutAfter 传 Long.MAX_VALUE。deferred 的 remainingMs 仍表示一小时生命周期。当前服务若尚未适配，这是剩余可见差距，不能宣称整套 user_ask 已完成设备对齐。
3. 主代理 UI / Aquinas 将工具卡片 Stop 路由到 VM.stopTool(blockId)，延迟 Stop 确认可校验 liveTurnToken，防止旧确认停止新轮；不要再把旧卡片 Stop 一律当作全会话 Stop。
4. ContextTrimPlanner 四阶段已执行接线；仍应进一步对照稳定版 summaryWouldFreeLittle / shouldEscalate 的摘要收益评估与低收益升级调度。当前复用 fork 的 inLoopContextCheck / compactAttemptedSinceDispatch，不能把纯 planner 用例通过等同于该完整策略已核验。
5. InFlightToolHeal 已用于界面状态和 overlay 合并；独立 stale-block 取消后的 DB 补 tool_result 尚未新增，正常 Stop 仍沿用 fork 已有取消持久化路径。若后续补独立 stale 恢复，使用 appendableCancelResults，只补匹配末尾且无结果的调用，避免重复/错位结果。
6. FolderOfferFormat 输出已接，但为避免修改主代理的数据范围，使用现有 DAO 逐组取成员；可由主代理后续提供稳定 titledFolderMembers 的批量查询以优化大量会话时的读取。
7. 等待 Aquinas UI/review/SubAgentTermination 最终交接；本代理没有提前改其文件或声称后续接线已完成。

新增/修改的主要测试：`agent/jobs/UserQuestionStable115Test.kt`、`ui/chat/Stable115LoopCoreTest.kt`、`provider/ResponsesIncompletePartialTest.kt`；之前的 EmptyTurnFallback / ThisTurn / LoopRecoveryIntegration / SignedToolArguments / AnthropicRefusalDetails 定向测试保留，当前69项结果不包含全部 provider 与 VM UI 集成测试。
