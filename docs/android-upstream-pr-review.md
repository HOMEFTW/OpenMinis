# Android 上游 PR 审查与移植（2026-09-14）

基于 fork `1f25a9665a85dc5433dad17c98efd20ae2a5393b` 选择性实现。没有整包合并上游分支，没有引入作者个人配置、截图、iOS 改动或 GitHub 工作流。

## 来源与决策

| PR | 审查的 head | 结果 |
| --- | --- | --- |
| [#346](https://github.com/OpenMinis/OpenMinis/pull/346) | `80ac3f1fb92f51a2331bf1cffa528a06e1e00c63` | 采纳三项稳定性修复，重写 WebView 持有者处理与卸载识别。 |
| [#322](https://github.com/OpenMinis/OpenMinis/pull/322) | `a53bbbcce9fcb64f3b708c2fe83fcc03a423ecd4` | 采纳本地备份、MCP 手动测试和简化用量筛选。 |
| [#260](https://github.com/OpenMinis/OpenMinis/pull/260) | `740aeb72027a8ae714846013b680c8184c3c6da9` | 仅对现有 `allowsEmptyAPIKey` 接口允许保存空密钥。 |
| [#331](https://github.com/OpenMinis/OpenMinis/pull/331) | `8db3ed575bfcc8722052a39c7e728669bcd962f4` | 不采纳全局 OpenCode 请求头。 |
| [#309](https://github.com/OpenMinis/OpenMinis/pull/309) | iOS draft | 不属于本 fork 的 Android 范围。 |

## 审查发现与修改

### 稳定性

- 强停应用会移除系统闹钟；重新启动且初始化成功后，重新注册持久化的已启用定时任务。沿用已有精确闹钟权限回退，不自动补跑已过期任务。
- 上游以“前 512 字符含 offloads 路径”判断卸载读回，可能把普通内容误判为已卸载。现在严格匹配 `file_read` 头、卸载目录、紧接其后的 stub，并拒绝 `.` / `..` 路径。
- 卸载写入失败不再替换为指向空路径的 stub；保留原文和未保存的图片数据。
- 原 PR 仅销毁 WebView，仍会留下池中的死视图及悬挂的异步等待。本次覆盖全部 8 处 WebViewClient：共用幂等销毁；KaTeX 池失效、释放等待并在下次渲染重建；旧公式渲染器回退原始公式；预览和技能页显示失败提示，关闭重开后创建新视图；WebApp 结束故障页面；Agent 浏览器移除故障标签、解除 JS/导航等待并返回工具失败。
- 不自动重放浏览器动作或反复加载故障网页；浏览器实时截图在失效时返回空结果，不把故障转成界面协程崩溃。WebView 中尚未保存的页面状态无法恢复。

### 本地备份

- 不再要求 rclone 远程目的地。完成后的本地包可通过系统文档选择器保存，或由用户打开分享菜单。
- 原 PR 在主线程复制整个包，并吞掉异常；本次在 IO 线程流式复制，支持协程取消，确认写入/关闭成功后才显示成功，打不开目标、写满或源文件丢失均报错。
- 保存时固定选择器打开前的源文件，限制源文件位于 `filesDir/Backups`；FileProvider 仅新增这一子目录。
- 已上传到所有远程目的地且本地包被清理时，不再显示无效的保存入口。保留原备份范围/凭据预览。
- 历史详情中只要本地包仍存在，也可再次保存或分享。应用内副本仍会随卸载丢失，界面明确提示保存到应用外。

### MCP 测试

- MCP 服务器列表每行新增手动测试，执行刷新连接及工具发现，不调用模型或 MCP 的业务工具。
- 服务器 ID 使用 shell 单引号转义，拒绝空白和 NUL；检查真实退出码、JSON 服务器 ID、工具数组、工具名称与数量，不仅检查 `count` 字段是否存在。
- 全局互斥，使用独立测试 shell；结束或取消后释放 shell。页面离开取消测试，配置改变清除旧测试结果。刷新会重新连接所选 MCP 服务器，应在没有使用该服务器的任务时手动操作。

### 免密与用量

- 复用已存在的兼容接口免密路由和请求层空鉴权头处理，仅补设置页保存按钮。OAuth 和未设置自定义地址的服务商仍保留原凭据要求；免密服务是否可用取决于真实服务端。
- 用量增加全部时间 / 近 7 天 / 近 30 天，以及模型 ID、名称、服务商搜索。日期使用滚动时间窗口；搜索筛选明细列表，总计仍表示所选时间全部用量。
- 保留消息级归属快照、历史估算与孤立记录；同名模型的不同服务商不会合并到同一统计桶。

## 未移植的部分

- #322 的无差别工具并发：不能证明同一轮工具独立，shell、浏览器及文件修改存在共享状态，会与当前取消/排队/任务终态语义冲突。
- #322 的价格数据库：新增表没有完整备份恢复设计；费用估算、详细请求榜单、整段复制/TTS 本轮未引入。
- #331 给所有服务商加 `x-opencode-session`，影响无关服务且没有每安装持久化语义；本轮不添加。后续如实际需要 OpenCode，限定目标端点并尊重自定义请求头后再接入。

## 验证

见下方最终验证记录。设备专项仍需 Android 真机：同时打开公式/预览时触发 renderer death；重开页面；强停后重启并等待定时任务；SAF 保存、取消和失败反馈；真实 MCP 初始化与刷新。没有调用付费模型接口，也没有宣称真机验证通过。

### 最终验证记录

- 执行 `scripts/build_android_windows.ps1 -RunAllTests`（现有 Zulu JDK 21、Gradle 8.13、Android SDK）：`BUILD SUCCESSFUL`。
- 148 个 JVM 测试类、1338 项测试，失败 / 错误 / 跳过均为 0。本轮新增 23 项（卸载识别、MCP 参数/响应、日期搜索、本地备份复制、关闭、失败与取消）。
- `git diff --check` 通过。
- APK ZIP 完整性、ARM64 原生依赖、loader 字节一致性、DEX 中 Astra 和 Image 2.5 模型 ID 均检查通过。
- `apksigner verify --verbose --print-certs` 通过，使用与前版相同的 Android Debug 证书（SHA-256 `e3536fc5f03bd72e1bc8073b306659cb4edc6b401fb4152048c403c347c08273`）。
- 交付包 `OpenMinis-upstream-reviewed-debug.apk`，69,071,266 字节；SHA-256 `31255beed723fdcbe0a951fa6405999008f4f59558cf69e5dacda040cd4d816f`。
- 构建日志位于本任务 `work/upstream-port-final-validation.log`；测试统计 `work/upstream-port-test-results.json`。
- 本轮没有连接 Android 设备，也未运行 instrumentation 测试。既有 androidTest 中 `ExecutionCoordinatorInstrumentedTest` 的 `mountedSessionId` 引用问题不在本次修改范围，未宣称该测试集通过。
- 以上为本地验证记录；这些功能现作为 Android 增强 PR 的一部分提交供上游参考。
