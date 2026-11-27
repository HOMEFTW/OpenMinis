# 2026-11-27 官方更新核对与本地对齐

## 官方发布与公开源码

2026-11-27 实时读取官方 GitHub Release、标签提交及提交对比：

| 项目 | 官方结果 |
| --- | --- |
| 最新稳定 Release | [Android 1.15 / build37](https://github.com/OpenMinis/OpenMinis/releases/tag/1.15)，2026-10-25 发布 |
| 稳定版源码 | `6eda5523a9c14fa8edc4fd9fb72e643fe47f23be` |
| 最新 Beta Release | [Android 1.16-beta.9 / build47](https://github.com/OpenMinis/OpenMinis/releases/tag/0-beta-build47)，2026-11-26 发布 |
| Beta 标签源码 | `1a71df0f381cefde80bf912b723de6f9831bc177`；Gradle 仍声明 1.15 / 37 |
| 两份源码的差异 | 3 个提交、9 个文件；Tailscale 出口节点维护补丁及 1.15 版本声明 |

官方 [源码对比](https://github.com/OpenMinis/OpenMinis/compare/6eda5523a9c14fa8edc4fd9fb72e643fe47f23be...1a71df0f381cefde80bf912b723de6f9831bc177) 没有包含 Beta39–47 发布说明中的大部分功能。GitHub 自动生成的源码压缩包对应标签提交，不能依据 APK 的版本名判断源码已经更新。

本次沿用此前先对齐稳定版的范围，先补齐这批 1.15 网络维护修复。Beta 新功能的范围选择尚待用户答复。

## Beta 近期更新摘要

以下来自官方发布说明，仅作为后续范围依据，不代表本轮已实现或已逐项验证本地缺口。

| 版本 | 主要新增与修复 |
| --- | --- |
| build47 | 文件回收站及受保护目录删除确认；最近使用的模型；压缩失败重试；会话 CLI 读文件/发送消息；修复大附件内存问题、长会话发送卡顿、文件消息丢失、重复编辑、问题卡片续跑和思考参数。 |
| build46 | 后台高 CPU 记录；修复以 `#` 开头的非标题行导致 CPU 死循环；GPT-6.2 缓存前缀；Rootfs 大小按需计算；悬浮窗口去重和关闭。 |
| build45 | GPT-6.2 Astra/Sol 及 Ultrafast；更多余额服务商；草稿移动；会话搜索模式；子代理汇总与工具折叠；修复相机重复启动、模型目录、备份合并及子代理输出截断。 |
| build44 | 桌面小组件、个人资料、DeepSeek 语音、新会话图标；修复前台服务、冷启动跳转、数学渲染、Copilot Responses、浏览器超时和工具重复保存。 |
| build43 | Claude Fable 5.2；浏览器全屏及人工接管；全局自动分组；环境变量导入导出；后台浏览器静音。 |
| build42 | 最近会话分区；自建 Headscale 控制服务器；Soul 3000-token 上限；修复状态栏、稀疏文件大小、语音波形、手册只读及 PATH 引号。 |
| build41 | 阿拉伯语/RTL、GPT Image 2.6、Fish Audio/自定义声音、朗读开关、逐会话备份恢复策略；修复备份消息顺序、会话副本文件隔离、响应超时、重复工具调用。 |
| build40 | 玻璃效果级别、无障碍保活、平板双栏设置、子代理 resume、PDF 预览；模型列表刷新、Responses 工具 ID 和超时/滚动修复。 |
| build39 | 待回答问题提示、文件拖至分组、清理临时文件、用量周期、挂载目录重绑；长输出/内存/重试/思考档位/备份等修复及玻璃界面。 |

如果后续选择 Beta 对齐，应先对照本地已有等效实现，再按公开行为补充缺口。官方签名轮换、官方遥测及上游更新源不作为 fork 的直接迁移项。

### 已定位的 Beta 修复候选

流式聊天渲染器 `StreamingMarkdownText.kt` 的标题分支只接收部分 `#` 行，但段落分支遇到任何以 `#` 开头的行就退出，且未推进行号。因此 `#tag`、`##` 等非标题输入存在原地循环路径，与 build46 的高 CPU 修复说明相符。独立的 `MarkdownParser.kt` 段落分支会先消费当前行，不能用它的测试替代流式渲染器验证。本轮只完成源码定位，未运行该输入的回归，也未在稳定网络补丁中修改此处；列为 Beta 缺陷同步的优先项。

## 本轮实现范围

- Tailscale 出口的外部域名优先 IPv4，必要时回退 IPv6。
- `localhost`、`*.localhost`、127/8 及 `::1` 保持本机连接。
- 所选出口离线时拒绝 TCP/UDP 连接，避免意外改走直连。
- 出口无 IPv6 路径时，Shell 外部域名 AAAA 查询返回空地址，让程序使用 IPv4。
- 同步相关 Go 回归及共享路由测试向量；保留 fork 的 Tailscale 兼容修改。

不修改应用版本号（1.17.0 / 32）。保留本地已完成的发送错误、Forge 白屏和 GPT-only Fast 修复，以及已取消功能的现有占位状态。本次不提交或推送 Git，不发布 Release。

## 验证记录

- WSL Ubuntu / Go 1.25.5：`go test -mod=readonly -p=4 -json -tags=ts_omit_logtail,ts_omit_portmapper ./nethub ./tsnet ./rcloneroute ./netfs`，4 个模块、83 项测试全部通过，无跳过。包含上游全部新增用例、额外离线 UDP 回归及 22 条共享路由向量。
- 使用 `scripts/build_rclone_windows.ps1` 重建 Android ARM64 原生库，AAR SHA-256：`845004ed426d2805a9db7efbf6c3619edb5a650ef6eae5618c9081e0e1939f58`。构建固定 `GOTOOLCHAIN=go1.25.5`，保留原项目依赖版本。
- 使用 `scripts/build_android_windows.ps1 -TestFilter ...` 完成 18 个套件、116 项 Android JVM 回归（网络模块、服务商路由及系统提示/发送分发），全部通过，无跳过。`BUILD SUCCESSFUL in 24s`。
- APK 初次增量打包含约 16 MB 的旧 ZIP 空间，内容和签名校验正常；随后使用同一构建脚本的 `-Clean` 重新打包。该步骤不修改源码或 AAR，复用已保存的同源 JVM 测试结果。
- 干净构建 `BUILD SUCCESSFUL in 2m 58s`。最终 APK 的 ZIP CRC、重复条目、v2 签名及 `zipalign -c -P 16 -v 4` 均通过。签名证书与上一测试包相同；7 个 Forge 前端资源与源码生成产物一致，发送修复及原有定制类仍在 DEX 中。
- 校验了 AAR → Gradle 合并库 → 去符号库 → APK 的完整链路。去符号步骤改变 ELF 元数据，所有加载段对应节的内容及布局保持一致；APK 包含新补丁标记，原生库 SHA-256 为 `cc2b91c1d80151205aaf8434f5aab902165d16282e80afa0b01bdceb0cc0682f`。PRoot 与上一测试包一致。
- 同步前 26 处已跟踪差异、11 个未跟踪源码文件与备份一致；`git diff --check` 通过。六个移植文件与上游完全一致，`tsnet.go` 仅保留原有平台兼容适配，新增测试文件在上游用例之外增加离线 UDP 用例。
- ADB 无连接设备。未执行真机安装、真实 Tailscale 出口切换/断线、Shell 网络互通或真实模型 API 测试。

工具、官方元数据、完整中文发布说明、同步前差异备份、Go/JVM 日志及验证产物位于 `D:/Code/.tools/openminis-upstream-20261127/`。

## 本地交付

- APK：`D:/Code/.tools/openminis-upstream-20261127/OpenMinis-1.17.0-upstream-network-fix.apk`。
- 包名 `com.openminis.app`，1.17.0 / versionCode 32，Android 8.0+，ARM64。
- 大小 78,902,198 字节；SHA-256：`7b81dde21d9c9bba27912c1c89bc569902915554adaf9dd7cbc48cc83ce764be`。
- 机器可读验证：同目录 `verification.json`、`go-test-summary.json`、`preservation.json`。源码修改与 APK 均保留本地，未发布至 GitHub。
