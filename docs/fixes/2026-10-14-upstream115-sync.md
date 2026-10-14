# Android 1.15 beta 行为同步与 1.16.2 验收

基线：HOMEFTW/OpenMinis main / 858ecfde6d62103f3639b5967dfe990501057a1c，Android 1.16.1（29）。本次发行 1.16.2（30），使用原包名、签名及 GPT／DeepSeek／Claude 品牌资源。

用户授权两名子代理同步、提交、推送及发布 APK。文字界面与沙箱／会话工具菜单各由一名子代理实施；主代理接入问答、通知、上下文、朗读、并发、外观开关及发行。独立审查采用只读方式，共享文件保持唯一写入负责人。

## 上游依据

- [1.15-beta.3 / build 32](https://github.com/OpenMinis/OpenMinis/releases/tag/0-beta-build32)。
- [1.15-beta.4 / build 33](https://github.com/OpenMinis/OpenMinis/releases/tag/0-beta-build33)。
- 两个 annotated tag 均解引用到 b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c，与公开 main 一致，源码仍声明 Android 1.14。GitHub 自动生成的 Source code 下载包含该提交的源码，不能据此断言 APK 的 beta 实现已经公开。
- 本次依据公开发布说明和 APK 的兼容性证据补齐 Android 等效行为，保留本 fork 的原生 DeepSeek 协议、模型支持、上下文设置、100 MiB 图片预算、会话通信及既有可靠性修复。

## 本次补齐

| 行为 | 实现及边界 |
| --- | --- |
| 后台问答与直接回复 | 单独通知逐题显示选项、确认的 Allow／Cancel，以及 RemoteInput。会话、批次 token 和当前题 ID 共同验证回复；关闭 Other 时仍拒绝列表外输入。通知点击打开对应会话。 |
| 等待倒计时及提问缩略图 | 聊天卡片、状态栏、常驻通知和 Android 16 胶囊共用问题状态。默认整批等待五分钟，可配置 1–3600 秒；超时、取消和任务结束清理等待，不视为许可。 |
| 问题卡片与类型兼容 | 按字段推断缺失／未知类型；逐题推进，保存当前选项及独立文字草稿。由聊天列表负责滚动，避免嵌套滚动争抢。 |
| 空闲常驻通知 | 仅打开会话时保留必要保活，显示就绪／会话状态，不出现运行计时和 promoted chip；运行、排队及待确认完成状态分别处理。 |
| 长任务上下文维护 | 摘要前按最旧顺序回收无签名思考、已有大输出及完整旧工具步骤；仅回收实际发送视图内的内容，保留最新四条。完整步骤写入会话 offload 文件，历史中保留工具调用／结果配对、角色、行 ID、错误状态和文件回读入口。数据库原文不变。 |
| 六会话并发 | 最多六个会话运行，第七个等待名额；既有 FIFO、取消及异常释放路径保留，每会话子代理上限仍为三。 |
| MCP／Skills 面板 | MCP 搜索、按过滤结果启停、脱敏只读服务器详情；技能正文详情及 URL 来源长按复制。返回详情或清除搜索不重置会话覆盖值，不改变全局默认。 |
| 朗读音量及长按设置 | 独立音量持久化，云端播放器实时调节，系统 TTS 从下一句生效；保留原有音频焦点 ducking。长按工具状态栏切换预览，长按思考块切换流式展开。 |
| 停止前确认开关 | 默认确认，设置 → 外观中可关闭；主会话与子代理复用同一停止确认入口，配置工具使用同一偏好键。 |
| DeepSeek 工具误写恢复 | 仅在 DeepSeek 正常完成、没有正式工具调用且思考中包含当前可用工具的强序列化标记时触发。最多两次重试，请模型经正式通道发起调用；失败后明确提示并提供继续入口。不执行思考文本，不占用普通空回复重试预算。 |
| Kimi Code 订阅用量 | 复用登录 Token 及可取消的刷新请求，按官方 CLI 的 `/coding/v1/usages` 解析每周和时间窗口额度。显示加载、无数据、错误、过期快照和刷新状态；不以本地聊天 Token 统计代替订阅用量。 |
| MiniMax／OpenCode 控制台 | 仅对已核实的官方主机显示入口，跳转固定官方控制台 URL；用户 API 路径、查询参数和凭据不会成为跳转地址。 |
| 孤立会话文件清理 | 共用存储登记与维护协议，保护数据库会话、消息引用、草稿、迁移、运行中任务及终端子进程。用户扫描、确认后只处理扫描清单中的已知包内目录，删除前重新检查引用与身份，拒绝跟随符号链接；错误或安全删除能力不足时保留文件。 |
| 表格及转写编辑 | 实际流式 Markdown 渲染支持列表嵌套表格，保留缩进和续行；转写编辑双击按 TextLayoutResult 选词，不吞原有 TextField 手势；English 按钮单行。 |
| 沙箱 Git／Unicode | 修复旧 core.createObject=copy 配置并采用有效 rename 模式；移除登录脚本的旧写入命令。UTF-8 流解码保留，新增头尾、stderr 长行及最终截断的 UTF-16 代理对保护。 |

## 兼容性与保留差异

- 上游 build 32 Android 8–10 首次 Shell 崩溃来自 API 30 才有的 Os.fcntlInt 调用；build 33 增加 SDK 判断。本 fork 的 NativeOffloadServer 没有该调用，不重复引入它或更换已经验证的 Termux loader。
- 公开两版 APK 与本地基线的 PTY／PRoot／loader 做了 48 组 API 26–29 强导入及依赖静态对照，没有发现强导入缺失；这不等于 Android 8–10 首启验证。
- DeepSeek／Anthropic opaque replay 及 Gemini thoughtSignature 不做旧步骤或推理改写，避免破坏原生协议签名；这些内容继续走既有上下文压缩路径。
- 问答路由仅在当前进程内有效，通知不会恢复已丢失的任务；旧通知、已答题、过期批次和跨会话回复均被拒绝。
- 保留显示孤立子会话并由用户处理的行为，不引入启动时自动永久删除会话。

## 验证与发布

使用项目脚本 `scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests`，最终构建在 4 分 5 秒完成，应用及完整 Android 仪器测试包编译成功。

| 检查 | 实际结果 |
| --- | --- |
| Windows 全套 JVM | 329 个测试套件、2614 项测试，2607 通过、0 失败、0 错误、7 项跳过 |
| Windows 跳过项 | 5 项 SecureDirectoryStream 实际目录测试、2 项 POSIX 权限测试 |
| Linux 存储补测 | 使用最终 Gradle main/test 编译类，在 WSL Ubuntu、JRE 21 上运行 40 项；39 通过、0 失败、1 项“不支持安全目录流”反向能力测试跳过 |
| 安全删除实际路径 | Windows 跳过的 5 项在 Linux 全部通过，包含真实删除计数、确认后新增文件、替换身份、符号链接及已知布局筛选 |
| 应用及仪器包签名 | APK v2；证书 SHA-256 均为 `e3536fc5f03bd72e1bc8073b306659cb4edc6b401fb4152048c403c347c08273`，与本 fork 旧版一致 |
| 应用包完整性 | ZIP CRC、条目唯一性、20 个 DEX 的 SHA-1／Adler32 校验、16 KiB 页 ZIP 对齐均通过 |
| 原生与品牌资源 | ARM64 PRoot／rootfs 存在，MCP 文件与源码一致；44 个品牌及启动图标资源与 1.16.1 逐字节一致 |
| Shell 登录脚本 | 包内与源码逐字节一致，2173 字节、LF；旧 `core.createObject=copy` 写入命令已移除，迁移及有效 rename 配置由 PRootKernel 提供 |
| 新实现入包 | 问题回复 receiver、DeepSeek 恢复、存储维护 gate 和 Kimi 订阅用量 client 均在 DEX 中 |

应用 APK：70,849,882 字节，SHA-256 `a045d9d1293b0047a2e9e5e2b840f870ba6d657682fe47e2dd71b859e654c872`。

仪器测试 APK SHA-256：`46557a25a38a191b7ec6a346248fbb5272a7a47e0a1350c30199e7d4e6a43a31`。仪器包仅供设备测试，Release 安装附件为应用 `app-debug.apk` 及其 `app-debug.apk.sha256`。

独立审查发现的三项存储接入问题已修正：命令先登记再等待维护以便 Stop 取消；编辑分支初始化异常仍释放 reservation；detached tracer 用挂起轮询等待真实退出，避免长期占用 IO 线程。停止等待和实际进程寿命回归通过，并纳入最终全套测试。

Linux 首次补测误加载 Android 模拟 JSON 类，两个生产者测试失败；调整验证脚本 classpath 后重跑上述 40 项通过。附加 APK 检查曾把 Shell 注释误判为配置命令，现检查实际非注释内容；没有修改生产逻辑绕过验证。

[1.16.2 源码](https://github.com/HOMEFTW/OpenMinis/tree/1.16.2) · [源码变更](https://github.com/HOMEFTW/OpenMinis/compare/1.16.1...1.16.2) · [Release 与 APK](https://github.com/HOMEFTW/OpenMinis/releases/tag/1.16.2)。

没有连接 ADB 设备；不把 JVM、局部 Compose 编译或 ELF 静态检查称为真机验证。尚未执行 Android 8–10 首启、通知直接回复、真实触控、录音、后台 Doze、系统 TTS 播音或真实模型服务商联调。
