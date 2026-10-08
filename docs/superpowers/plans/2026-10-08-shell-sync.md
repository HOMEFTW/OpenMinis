# 第二批：Shell 执行层同步

基于公开上游 b4c0661；2026-10-08 GitHub API 确认 main 未更新。beta 32 发布说明用于核对行为，不宣称完整合并未公开代码。

1. 同步独立命令进程、进程组退出状态、标准错误排空与进程预算逻辑；保留本地日志脱敏、环境变量、PRoot 参数及安全限制。
2. 接入现有 ExecutionCoordinator：同会话命令隔离，跨会话共享进程预算；停止及会话结束同时覆盖正在执行和等待的命令；取消不可重试已经执行的命令。
3. 增加回归：UTF-8 分块、短输出、退出码、命令转义、超时/停止、队列取消和并发上限。原生 Android 进程行为无法以 JVM 测试替代，明确真机验证限制。
4. 使用 scripts/build_android_windows.ps1 -RunAllTests 构建并检查差异。保留上一批全部修改，不提交/推送/发布。

本批不整体替换 ChatViewModel、数据库或原生二进制；新子代理体系另需持久化、工具和 UI 的完整迁移。更新源/渠道维持原配置，避免定制 APK 与上游签名混用。

## 实施结果

- ExecutionCoordinator 已接入每次命令独立 PRoot 进程，保留会话挂载、环境变量、代理/时区传递和已有 seccomp 回退策略。旧 PersistentShell 不再承接 Agent 命令，交互终端不受影响。
- 从上游适配进程组 runner、独立退出状态文件、标准错误排空、进程预算队列及退出码格式。保留本地日志脱敏，未加入当前原生二进制未核实支持的 fake-netlink 参数。
- 本地补充：停止覆盖等待队列；取消等待进程清理后释放名额；并发预留在锁内完成；无法读取 /proc 时仍保守限制为最多四条同时运行的 Agent Shell 命令。
- stdout/stderr 保存有界的开头与结尾，超大输出不再无限积累；UTF-8 使用有状态解码，末尾无换行的短输出也交给回调。
- 2026-10-08：`scripts/build_android_windows.ps1 -RunAllTests` 成功。1505 项 JVM 测试通过，其中本批新增 24 项；命令转义/退出状态用本机 Git Bash 执行实际 runner，取消/超时使用可控进程替身，队列使用虚拟时间测试。`git diff --check` 通过。
- APK：`src/android/app/build/outputs/apk/debug/app-debug.apk`；SHA256：`95A14D489C3BC4455FBAD8E73B5716A42AEB0C28235AA9611C799D5C575D6D03`。
- 安装包内确认存在 `usr/bin/setsid`。未进行 Android 真机/PRoot 实际进程树终止验证，不能用 JVM 测试替代；未更换原生二进制。Git fetch 遇连接重置，已通过 GitHub API 验证 main 与 beta 32 标签均指向 b4c0661。
- 上一批修改及无关未跟踪文件保持保留。未提交、推送或发布。
