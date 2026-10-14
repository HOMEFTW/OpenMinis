# OpenMinis Android 增强版

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://github.com/HOMEFTW/OpenMinis/releases)

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的个人 fork，专注 **Android 模型接入、上下文管理、子代理、会话通信与个性化聊天界面**。

OpenMinis 是原生移动端 AI Agent：连接你自己的模型服务，在手机上使用 Linux 沙箱、文件工作区、浏览器、技能和记忆。本 fork 保留原项目架构，仅增加 Android 端改动；它不是上游官方发行版。

**[下载 Android 1.16.2 APK](https://github.com/HOMEFTW/OpenMinis/releases/download/1.16.2/app-debug.apk)** · [版本说明](https://github.com/HOMEFTW/OpenMinis/releases/tag/1.16.2) · [上游项目说明](README.upstream.md) · [构建指南](docs/android-windows-build.md)

**1.16.2 · 2026-10-14**：补齐上游 1.15 beta 的后台提问通知、等待倒计时、MCP／Skills 菜单、朗读音量与长按开关；支持六个会话同时运行，长任务在摘要前逐步回收旧思考及工具步骤。新增手动清理孤立会话文件、Kimi Code 订阅用量和 MiniMax／OpenCode 官方控制台入口，修复 DeepSeek 工具误写进思考时的恢复、列表嵌套表格、转写双击选词、沙箱 Git 配置及 Unicode 输出边界。源码以 [1.16.2 标签](https://github.com/HOMEFTW/OpenMinis/tree/1.16.2) 为准，详见 [同步与验收记录](docs/fixes/2026-10-14-upstream115-sync.md)。

上游 build 32／33 的 Source code 下载确实存在，但标签指向的公开源码仍声明 Android 1.14。本次按公开发布行为补齐 Android 等效实现；不是直接合并未公开的 beta 实现。保留本 fork 的模型接入、上下文设置、100 MiB 图片预算、会话通信和三套品牌图标。

## 应用品牌图标

默认使用 **GPT 龙娘**。在 **设置 → 外观** 中可切换 GPT、DeepSeek、Claude 三套图标，桌面入口、关于页、最近任务、通知和悬浮入口使用对应品牌素材。图标选择独立于随模型切换的对话背景；已固定的旧网页快捷方式不会自动替换图标。

| GPT（默认） | DeepSeek | Claude |
| --- | --- | --- |
| <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" alt="GPT 龙娘图标"> | <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground_light.png" width="100" alt="DeepSeek 图标"> | <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground_dark.png" width="100" alt="Claude 图标"> |

[品牌素材来源](src/android/app/src/main/assets/brand_icons/SOURCES.txt)

## AI 对话背景

DeepSeek、GPT、Claude 模型自动使用对应背景；对话中切换模型或自动回退时同步切换。图片在输入框上方完整缩放显示，避免人物脚部被遮挡。

<table>
  <tr><th>DeepSeek</th><th>GPT</th><th>Claude</th></tr>
  <tr>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_deepseek.webp" width="220" alt="DeepSeek 鲸鱼娘背景素材"></td>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_gpt.webp" width="220" alt="GPT 白发龙娘背景素材"></td>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_claude.webp" width="220" alt="Claude 橙发书卷娘背景素材"></td>
  </tr>
</table>

上图为背景素材预览。背景默认开启，默认透明度 **75%**（0% 完全显示，100% 完全透明）。

- **设置 → 外观 → AI 对话背景**：调整默认透明度。
- **对话右上角菜单 → 对话背景 / 背景透明度**：开关当前背景、单独调整透明度或恢复默认。
- 未识别的模型使用纯色背景；按实际模型 ID 识别，不按兼容接口或服务商昵称猜测。

[素材来源与授权说明](src/android/app/src/main/assets/chat_wallpapers/SOURCES.txt)

## 本 fork 做了什么

| 方面 | 改动 |
| --- | --- |
| 模型接入 | 支持 GPT-6 Astra / Sol / Luna、GPT-6.1 Sol 及 Image 2.5；思考参数按模型支持范围发送，GPT-6.1 Sol 默认 high。 |
| DeepSeek 直连 | 独立 DeepSeek 服务商与 API Key 入口，依据官方 Harness 接口实现请求、思考回放、工具调用和流式处理，不经过 OpenAIProvider 或 AnthropicProvider。 |
| 子代理与调度 | 子代理定义、模型选择、父子会话、后台进度、引导/停止/恢复及结果回调；定时任务支持即时执行、倒计时和忙碌会话投递。 |
| 后台问答 | 模型提问可在后台通知中逐题回答，支持选项、允许／取消和文字回复；等待状态显示倒计时，过期或旧通知不能回答新问题。 |
| 会话工具菜单 | MCP 支持搜索、对过滤结果批量启停和服务器详情；Skills 可查看正文，长按网址来源技能复制原始来源地址。 |
| 服务商用量 | Kimi Code 登录后显示服务端订阅额度与刷新状态，独立于本地 Token 统计；MiniMax／OpenCode 服务商详情可打开官方控制台。 |
| 存储维护 | 扫描并确认后手动清理孤立会话目录；保护已有会话、媒体引用、草稿、迁移及运行中的任务和终端，清理前重新核验，不在启动时自动删除。 |
| 朗读与交互 | 朗读胶囊提供独立音量滑块；长按工具状态栏开关预览，长按思考块切换流式展开；外观设置可关闭停止前确认。 |
| 输入区 | 模型与思考强度入口移入输入框，使用可搜索模型列表和分档滑块；保留模型分组、当前项勾选及详细选择器。 |
| 上下文管理 | 独立设置页提供 64K / 105K / 272K / 1M 预设及 8K–1M 自定义上限；结合全局、模型与模型组限制，按服务端输入用量校准预算，计入 Anthropic 缓存 Token。 |
| 会话通信 | 支持手动文本转发及 AI 主动请求，提供接收授权、持久化排队、关联回复和防循环保护；不自动合并两边历史。 |
| 生图结果 | 修复已返回图片却只保存提示词的问题。JSON 输出包含 `media_files` 清单，多图全部保存；无图片、下载失败及写入失败明确报错。 |
| 图片预算 | 单图 5 MiB、单次请求图片预算 100 MiB；校验编码与 MIME，按需加载历史图片，超预算时保留原附件。服务商自身限制仍适用。 |
| 会话隔离 | 输入 JSON、系统文件、参考图及输出按调用会话解析，避免并行任务读写其他会话目录。 |
| 任务结果 | 区分本次运行的成功、失败、取消、超时和预算停止；排队批次分别记录，定时任务失败也写入历史。 |
| 凭据与日志 | 加密存储失败时保留原数据，使用有提示的进程内临时存储；日志和分享副本脱敏。 |
| 终端显示 | 修复回车覆盖、ANSI 和 OSC 序列处理，保留合法 `null`、空白和文本。 |
| 启动与草稿 | 修复重复会话列表，恢复最后查看的对话；持久保存草稿并提供草稿箱。 |
| 数据恢复与分享 | 恢复已有会话时保留消息和压缩标记，旧备份不覆盖较新正文；备份包含嵌套媒体引用，副本独立保存资源，分享在草稿保存成功后才确认消费。 |
| 工具可靠性 | MCP 副作用结果丢失时报告结果未知，避免自动重复执行；正确处理 SSE 事件。同文件编辑与写入协调，大文件窗口读取限制内存使用，过期浏览器/终端回调不影响新请求。 |
| 调度与配置 | 并发准入、排队和取消避免名额泄漏；定时任务队列保活到接管或终态。后续请求刷新凭据与服务商配置，统一执行条目思考上限。 |
| 上游同步 | 同步公开 Android 源码中的模型刷新、浏览器生命周期、语音重试、子会话备份、存储清理及 PRoot 沙箱更新，保留本 fork 的上下文、图片预算与会话通信能力。详见 [同步记录](docs/superpowers/plans/2026-10-08-remaining-sync.md)。 |
| 日常使用 | 新增任务中心、跨会话作品库、保留原历史的编辑分支、分类错误建议、Markdown 导出和备份预览。 |
| 任务工具 | 新增任务成果列表、轮数预算、上限及重试提醒，以及服务商实际调用自检。 |
| 长对话稳定性 | 摘要前按最旧顺序回收无签名思考及完整工具步骤，保留回读文件、原数据库历史和最新四条；限制 Markdown 解析、嵌套与缓存，超长内容支持分块全文查看和导出。图片发送同时检查文件大小与像素尺寸。 |
| 标题与反馈 | 自动标题严格解析并限制等待时间，不覆盖手动改名；关于页与反馈统一指向本 fork，反馈仅保留 GitHub Issues。 |
| 超时恢复 | 子代理超时后可恢复原子会话，等待旧任务收尾，避免新旧运行互相覆盖。 |
| DeepSeek 恢复 | 识别误写进思考内容的工具调用标记，最多请求两次正式工具调用，失败后提供继续入口；不直接执行思考文本，保留输出上限及断流提示。 |
| 语音转写 | `android-speech` 支持麦克风或授权目录中的音频文件，复用系统或已配置的语音服务商，处理超时、取消及资源限制。 |
| 端点证书 | 为指定 HTTPS 主机与端口配置 PEM CA，保留证书链和主机名校验；可清除恢复默认信任。 |
| 记忆与兼容性 | 近期记忆日志可独立关闭；统一 Claude 认证与聊天请求头；识别特定 PRoot 断言后，对后续 Shell 使用兼容模式，不自动重放命令。 |

## 安装与使用

1. 从 [Releases](https://github.com/HOMEFTW/OpenMinis/releases) 下载 APK。当前构建面向 **Android 8.0+、ARM64**，使用调试签名；不是上游官方发行版，尚未完成真机验收。
2. 在服务商设置中填写自己的接口地址和凭据，刷新模型列表。可用模型取决于服务商和账号权限；项目不附带 API Key 或模型额度。
3. 输入框内点击模型名切换模型，点击思考强度打开滑块。滑块只提供当前模型允许的档位；服务商自定义模型能力也会参与判断。
4. 图片模型通常作为 Agent 工具使用：在模型分组设置中开放给 Agent，再让文本模型调用 `minis-model-use`。

DeepSeek 官方 Key：添加服务商时选择 **DeepSeek** 并填写密钥。旧兼容服务商配置保持不变，不会自动迁移。此入口使用官方 Harness 同款 `https://api.deepseek.com/anthropic/v1/messages` 与 `x-api-key`，由独立 DeepSeek 实现处理；不是另造私有协议。详见 [接入与验证记录](docs/superpowers/plans/2026-10-08-deepseek-native.md)。

新增入口：

- **会话列表右上角菜单 → 草稿箱 / 任务中心 / 作品库**：找回草稿、管理本次进程任务和浏览跨会话文件。
- **长按用户消息 → 在新分支中编辑**：保留原会话和回答，附件独立复制。
- **长按会话 → 导出 → Markdown**；**备份 → 开始备份**前展示范围、凭据类别及大小估算。
- **设置 → 上下文大小**：调整全局上限，默认 105K；保存后生效，实际窗口仍受模型和模型组上限约束。
- **聊天右上角「⋯」→ 会话通信**：手动发送、查看回复，以及开启本会话的 AI 消息接收权限。
- **聊天菜单 → 任务成果**：查看当前会话文件，预览、分享和刷新。
- **聊天菜单 → 任务预算**：10 / 25 / 50 / 100 / 200 轮，默认 200。这是执行轮数限制，不是金额限额。
- **服务商设置 → 测试调用**：选择模型并点击发送后进行实际文本调用，可取消，可能消耗接口用量。
- **设置 → Kimi Code 服务商详情 → 订阅用量**：使用登录凭据读取服务端额度，可手动刷新；MiniMax／OpenCode 的官方接口服务商详情提供官方控制台入口。
- **设置 → 存储 → 清理孤立会话文件**：先扫描，再确认清理；仍被引用或正在使用的文件会保留。
- **模型列表 → 更多模型与分组**：原有详细选择器、分组管理和模型测试。
- **设置 → 服务商详情 → 自定义服务器证书**：粘贴 PEM CA，仅对配置的 HTTPS 主机与端口生效；同端点账号共享配置，留空可移除。
- **设置 → 记忆 → 自动加入近期记忆日志**：默认开启，关闭后从下一轮生效，不影响全局记忆和记忆工具。
- **设置 → 反馈问题**：[本 fork 的 GitHub Issues](https://github.com/HOMEFTW/OpenMinis/issues)。

音频工具示例：`android-speech transcribe --source /var/minis/workspace/audio.wav --engine provider`。`--source mic` 使用麦克风，`--engine auto|system|provider` 选择引擎；文件须位于授权目录，最多 25 MiB、300 秒。系统文件转写依赖 Android 13+ 及设备识别服务支持。

不同签名的官方版或第三方 APK 不保证能够覆盖安装，请先通过应用备份功能保留数据。本 fork 的同签名测试版可尝试直接更新。

## 会话通信怎么用

- **手动发送**：打开一个已有会话 → 右上角「⋯」→ 会话通信，选择目标会话，输入文本或点击“填入最近一次 AI 回复”，再点击“发送并在目标会话执行”。
- **AI 主动通信**：先在目标会话的通信面板开启“允许其他会话的 AI 向本会话发送请求”，再让发送方 AI 联系它。AI 使用 `session_message` 查询目标、异步发送和读取结果。
- **查看进度与回复**：双方通信面板都可查看排队、处理中、完成或失败状态。目标忙碌、有未完成任务或未发送草稿时继续等待；需要审批或停止执行时，前往目标聊天页。

通信使用目标会话的模型和权限，会消耗 Token。当前仅发送文本，不自动携带附件或完整历史；回复不会自动唤醒发送方继续对话。应用重启后恢复尚未启动的队列，已启动但中断的请求不会自动重放。更多限制见 [会话通信说明](docs/android-session-communication.md)。

## 生图输入格式

Image 2.5 的 Images API 使用以下默认格式，提示词按任务填写：

```json
{
  "prompt": "一只红熊猫宇航员，电影摄影风格",
  "size": "1024x1024",
  "quality": "high",
  "n": 1
}
```

`--output /var/minis/workspace/result.json` 保存结果清单，图片路径在 `media_files` 中。输出必须使用绝对 Linux 路径。OAuth 生图使用原有 Responses 工具通路，其参数和模型权限由服务商决定。

## 从源码构建

先按 [BUILDING.md](BUILDING.md) 准备 Android SDK、NDK、Alpine、PRoot 和 `rclone.aar` 等依赖。**原生构建产物不放进 Git，首次克隆后不能跳过依赖准备。**

Windows 在已配置 JDK、Android SDK 和 Git Bash 的 PowerShell 中运行：

```powershell
.\scripts\build_android_windows.ps1 -RunAllTests
```

脚本复用 PATH 或本机缓存中的 Gradle，也支持 `-GradlePath`。APK 输出到 `src/android/app/build/outputs/apk/debug/app-debug.apk`。详见 [Windows 构建说明](docs/android-windows-build.md)。

## 验证情况

2026-10-14，1.16.2 本机验证：通过 `scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests` 运行 **2614 项 JVM 单元测试**，2607 通过、0 失败、0 错误、7 项跳过；其中 5 项依赖 Windows 不支持的安全目录流，2 项依赖 POSIX 目录权限。覆盖后台问答的批次与回复校验、上下文回收、DeepSeek 恢复、服务商用量、存储维护及已有可靠性回归。

使用最终 Gradle 编译类在 WSL Linux 补测存储：40 项、39 通过、0 失败、1 项因 Linux 支持安全目录流而跳过的反向能力测试。Windows 跳过的 5 项安全删除测试在 Linux 全部通过，覆盖确认后新增文件、目录身份替换、符号链接及真实删除；这是 JVM 文件系统验证，不等于 Android 设备验证。

应用与完整 Android 仪器测试包均编译成功并通过 v2 签名检查。应用 APK 的 ZIP 完整性、20 个 DEX 校验和及 16 KiB 页 ZIP 对齐检查通过；44 个品牌/启动图标资源与 1.16.1 一致，沿用本 fork 原签名。包内 Shell 登录脚本与源码一致、保持 LF，未重新写入旧 Git 配置。

安装包 SHA-256 见 [1.16.2 Release](https://github.com/HOMEFTW/OpenMinis/releases/tag/1.16.2) 附带的校验文件。完整清单见 [同步与验收记录](docs/fixes/2026-10-14-upstream115-sync.md)。

当前没有连接 ADB 设备，未执行真机安装、仪器测试、通知直接回复、触控选词、后台 Doze、系统 TTS 或真实服务商联调。这是本地执行记录，不代表 GitHub CI 或真机验收；开发验证使用模拟接口，没有自动调用用户的付费 API。上下文 Token 预算仍为校准估算，不是精确 tokenizer 计数。

本轮也未进行真实 Claude OAuth、外部语音服务及设备录音/解码验收。PRoot 改动只缓解明确的 seccomp 断言，不代表修复所有挂载/FUSE 问题；iOS 问题不在本 fork 的修复范围。

## 更多说明

- [2026-10-14 Android 1.15 beta 同步与 1.16.2 验收记录](docs/fixes/2026-10-14-upstream115-sync.md)
- [2026-10-09 上游问题修复及验证边界](docs/superpowers/plans/2026-10-09-upstream-issues.md)
- [2026-10-12 功能缺陷修复及验收记录](docs/fixes/2026-10-12-functional-reliability.md)
- [三套品牌图标实施记录](docs/superpowers/plans/2026-10-09-brand-icons.md)
- [会话通信：手动转发、AI 请求、排队与权限](docs/android-session-communication.md)
- [日常使用增强：八项功能、入口及限制](docs/android-daily-use.md)
- [日常使用实施计划](docs/superpowers/plans/2026-09-14-android-daily-use.md)

- [Astra 与 Image 2.5 接入、生图修复](docs/android-astra-image25.md)
- [任务可靠性与新增入口](docs/android-reliability.md)
- [输入区与思考滑块](docs/android-composer-controls.md)
- [修复实施清单](docs/superpowers/plans/2026-09-13-android-reliability.md)

## 上游与许可

感谢 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 项目及其贡献者。本仓库保留上游历史、版权声明和 **GPL-3.0** 许可；完整条款见 [LICENSE](LICENSE)。本 fork 仅维护 Android 端，iOS 相关介绍请参阅[上游 README](README.upstream.md)。
