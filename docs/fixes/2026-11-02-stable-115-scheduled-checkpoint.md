> 阶段交接归档：以下待办和测试数量仅描述当时状态。最终实现及验证以 [稳定版同步记录](2026-11-02-stable-115-sync.md) 为准。

# 定时与 backup UI 阶段交付

稳定参照：Android 1.15 build37，`6eda5523a9c14fa8edc4fd9fb72e643fe47f23be`。

本阶段不运行 Gradle，不修改 Min​isApp、任何 ViewModel、ui/chat、config、updater、网络或版本。主代理已同步的 BackgroundSettingsRepository、ScheduledTaskDetailModel/Card/Sheet 及 offload caller 归属不覆盖。

## 已实现

- `ScheduledSessionCoordinator.kt`：进程级观察任务、后台设置的 scheduledCountdownLead、进行中的交接/持久化 delivery 和 AgentJobRegistry。到提前量窗口及 calendar slot 边界自动重算，无需再有一次偏好写入。只有 AppendToSession 目标等于 creatorSessionId 才保持创建会话；其他目标和没有创建者的旧任务不误标 active。on-completion 只在上游存在且未完成时保持。
- `SessionActivityTracker.init` 安装观察器；观察器调用现有 `setScheduledSessions` 并安装 `cancelScheduledByUser`。等待态不占模型并发槽。最后一次 fire 已禁用任务时，交接和 delivery 仍持有会话，直至持久化结果完成。普通/Android 16 通知沿用前阶段倒计时，服务会话计数计入 scheduled hold。
- `ScheduledTask.kt`：新增可选 creatorSessionId、runNowSlotMs 和默认 0 的 stopGeneration，JSON 对旧任务兼容；calendar 的下次运行跳过已被 Run now 提前消费的 slot。
- `ScheduledTaskManager.kt`：在 ScheduledTaskStore 的全局锁内比较旧请求和待执行轮，并原子消费 Run now。AFTER 一次、INTERVAL 一轮且重新计时、CALENDAR 一个待执行 slot；已在路上的旧 calendar alarm 会被抑制。保持既有历史、executionId 去重、相对定时和参数合并规则。
- 保留 fork 的未保存/禁用任务试运行，以及 CLI 手动触发 on-completion 的能力；事件任务手动运行同样消费它唯一的事件，避免上游完成后再执行一次。已启用计划的 Run now 属于真实计划 fire，最后一轮完成后可释放依赖链。
- `ScheduledAgentRunner.kt`：统一 UI/CLI Run now 入口，交接阶段即预留，避免并发点击重复消费；持久化队列接入 hold。取消中的 delivery 只写取消历史；已消费 slot 的手动运行携带同一份计数快照。没有修改 ScheduledTasksViewModel。
- `ScheduledDeliveryQueue.kt`、`ScheduledSessionDispatcher.kt`：Stop 持久化取消队列并取消已准入但仍排队的 VM run，避免它在 Stop 后启动。stopGeneration 在批量 Stop 时与禁用状态同一次写入，即使旧 alarm 已拿到“最后一轮已禁用”的快照、或其后又重新启用，也能拒绝晚到交接。调度子代理使用每父会话准入及 HelperRunner.MAX_TURNS。
- `ScheduledTaskMarker.kt` / manager：delivery 是唯一 firedAt 来源；marker 和历史共用它。原 full6 的时间戳失败测试已改为真实 delivery JSON 往返、marker、历史及重复完成验证，不再数旧 runner 中固定格式的两行调用。
- `ui/scheduled/ScheduledTaskEditScreen.kt`：新任务编辑器明确以禁用的独立试运行请求调用原 VM，保留 fork 行为。

## 需主代理 / Euclid 接入

1. 本次只读检查时，`sandbox/offload/ScheduledTaskOffloadHandler.kt` 尚未出现 creatorSessionId 赋值。请主代理在已有 handleCreate 的 ScheduledTask 构造中补：

   ```kotlin
   creatorSessionId = callerSessionId?.takeIf { it.isNotBlank() },
   ```

   不能从 targetSessionId 推断创建者：显式发往另一会话的任务不应保持目标会话 active。本代理没有改此文件或覆盖已有 caller 归属修复。没有该字段，旧任务仍能执行，但不会被当作自己创建的会话 hold。

2. ChatViewModel 的用户全量 Stop 调用：

   ```kotlin
   ScheduledSessionCoordinator.stopCreatedBy(context, setOf(activeSessionId))
   ```

   通知上的全量 Stop 已通过安装的 callback 接好；“仅停当前工具”不调用此函数。此函数禁用计划并取消该创建者的 queued/active scheduled run，不阻塞等待整个 run 完成。

3. Chat/会话列表/Stop 可见性应观察 `SessionActivityTracker.scheduledSessions` 并与 activeSessions 合并，或采用 `isProcessing`；等待期间不要伪造 isStreaming。`hasBackgroundWorkToStop` 也需要考虑当前会话的 scheduled hold。此 UI/VM 部分属于 Euclid，未写入。

前六项助手提问/VM 接入仍见 `service/STABLE_115_LIFECYCLE_HANDOFF.md`，本轮没有重写它们。

## backup UI

- `BackupHistoryDetailScreen.kt` 使用 `BackupLogRules.omittedCount(entry)`，通过 `backup_history_log_omitted` 在当前语言中显示；默认、简中、繁中资源位于各自 `backup_history_log.xml`。不再显示原始存储 marker。
- `RestoreReportIssues.kt` 增加 restoreCredentialsNotice，`BackupAndRestoreScreen.kt` 调用它。无服务商恢复/解码失败/只有 unchanged 时隐藏“缺少凭据”；实际写入、保留、缺少凭据三种情况仍分别显示。fork 的 thinking rules/subagents 单独更新且 decoded provider 数为 0 时也不误报。

## 四个备份文件的只读差异审查

| 文件 | 相对稳定版的差异与结论 |
| --- | --- |
| BackupFileTreeExporter.kt | fork 提取 includeFile 并提供 exportFile，以导出工作区外被引用的聊天媒体。共用 snapshot 截止、blob、索引与跳过逻辑；应保留，没有发现遗漏的稳定功能。 |
| BackupRecordMapper.kt | fork 增加 messageIncomingWins，缺 revision 时不覆盖本地 ID；恢复真实 updatedAt 并执行 forBoundedStorage。稳定版会用 createdAt 且不做这些保护，应保留 fork。 |
| BackupRestoreFiles.kt | fork 增加 overwriteExisting 开关、SHA-256 格式验证、blob 源路径/符号链接验证。目标路径 containment 和 staged swap 仍共用；这些是安全增强，应保留。 |
| BackupZip.kt | 实现与稳定版一致，差异仅文档：fork 对有界 DEFLATE 与 ZIP64 的说明更准确；稳定版顶部仍称全部 STORED，但其方法体已经按内容选择压缩。无需改实现。 |

上述四文件未写入；本次是源码语义审查，没有重新运行其设备/大文件行为验证。

## 验证

`src/android/app/src/test/java/com/openminis/app/scheduled/standalone/Run-ScheduledTests.ps1` 使用缓存 Kotlin/JUnit 与依赖直接编译当前 coordinator、task/store/manager、runner、dispatcher、marker/detail、背景设置源码和恢复提示分类；不调用 Gradle、不联网、不启动 Android 服务。

结果：**52 tests，0 failures**，包含 full6 指定失败项。日志：`C:/Users/homeftw/AppData/Local/Temp/openminis-scheduled-e31584cedd81460ab97e02c978d3dc0b/tests.log`。`diff --check` 通过；三套省略提示资源 XML 有效且每套名称唯一。真实 AlarmManager/FGS、Compose 界面、实际模型/网络执行由主代理统一构建和设备验证。

交付后停止本阶段写入，接续只读网络与更新审查。
