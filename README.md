# OpenMinis Android 增强版

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://github.com/HOMEFTW/OpenMinis/releases)

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的个人 fork，专注 **Android 模型接入、图片生成、上下文管理、会话通信和任务可靠性**。

OpenMinis 是原生移动端 AI Agent：连接你自己的模型服务，在手机上使用 Linux 沙箱、文件工作区、浏览器、技能和记忆。本 fork 保留原项目架构，仅增加 Android 端改动；它不是上游官方发行版。

**[下载 Android 1.14 APK](https://github.com/HOMEFTW/OpenMinis/releases/download/1.14/app-debug.apk)** · [版本说明](https://github.com/HOMEFTW/OpenMinis/releases/tag/1.14) · [上游项目说明](README.upstream.md) · [构建指南](docs/android-windows-build.md)

当前安装包更新于 **2026-09-18**，对应源码提交 [`070e507`](https://github.com/HOMEFTW/OpenMinis/tree/070e5071fffcb0e4fd63d3b2761c64843df3f6f4)，包含会话通信和菜单图标更新。`1.14` 标签仍保留首发提交，查看当前 APK 的源码请使用此提交链接。

## 本 fork 做了什么

| 方面 | 改动 |
| --- | --- |
| 模型接入 | 增加 `gpt-6-astra`、`gpt-image-2.5-flare` 和 `gpt-image-2.5-sunburst` 的目录与请求适配。Astra 使用 Responses API，思考参数按模型支持范围发送。 |
| 输入区 | 模型与思考强度入口移入输入框，使用可搜索模型列表和分档滑块；保留模型分组、当前项勾选及详细选择器。 |
| 上下文管理 | 独立设置页提供 64K / 105K / 272K / 1M 预设及 8K–1M 自定义上限；结合全局、模型与模型组限制，按服务端输入用量校准预算，计入 Anthropic 缓存 Token。 |
| 会话通信 | 支持手动文本转发及 AI 主动请求，提供接收授权、持久化排队、关联回复和防循环保护；不自动合并两边历史。 |
| 生图结果 | 修复已返回图片却只保存提示词的问题。JSON 输出包含 `media_files` 清单，多图全部保存；无图片、下载失败及写入失败明确报错。 |
| 图片预算 | 单张图片 5 MB、单次请求 25 MB；压缩或移除超限图片，防止旧图片字段回退绕过限制。 |
| 会话隔离 | 输入 JSON、系统文件、参考图及输出按调用会话解析，避免并行任务读写其他会话目录。 |
| 任务结果 | 区分本次运行的成功、失败、取消、超时和预算停止；排队批次分别记录，定时任务失败也写入历史。 |
| 凭据与日志 | 加密存储失败时保留原数据，使用有提示的进程内临时存储；日志和分享副本脱敏。 |
| 终端显示 | 修复回车覆盖、ANSI 和 OSC 序列处理，保留合法 `null`、空白和文本。 |
| 启动与草稿 | 修复重复会话列表，恢复最后查看的对话；持久保存草稿并提供草稿箱。 |
| 上游精选 | 加入 WebView 故障处理、启动闹钟重注册、卸载读回保护、本地备份保存/分享、MCP 测试、免密保存及用量筛选。详见 [审查记录](docs/android-upstream-pr-review.md)。 |
| 日常使用 | 新增任务中心、跨会话作品库、保留原历史的编辑分支、分类错误建议、Markdown 导出和备份预览。 |
| 任务工具 | 新增任务成果列表、轮数预算、上限及重试提醒，以及服务商实际调用自检。 |

## 安装与使用

1. 从 [Releases](https://github.com/HOMEFTW/OpenMinis/releases) 下载 APK。当前构建面向 **Android 8.0+、ARM64**，使用调试签名；不是上游官方发行版，尚未完成真机验收。
2. 在服务商设置中填写自己的接口地址和凭据，刷新模型列表。可用模型取决于服务商和账号权限；项目不附带 API Key 或模型额度。
3. 输入框内点击模型名切换模型，点击思考强度打开滑块。滑块只提供当前模型允许的档位；Astra 当前映射为 `low / medium / high / xhigh / max`，旧 `ultra` 偏好兼容映射为 `max`，“关闭”映射为 `low`。
4. 图片模型通常作为 Agent 工具使用：在模型分组设置中开放给 Agent，再让文本模型调用 `minis-model-use`。

新增入口：

- **会话列表右上角菜单 → 草稿箱 / 任务中心 / 作品库**：找回草稿、管理本次进程任务和浏览跨会话文件。
- **长按用户消息 → 在新分支中编辑**：保留原会话和回答，附件独立复制。
- **长按会话 → 导出 → Markdown**；**备份 → 开始备份**前展示范围、凭据类别及大小估算。
- **设置 → 上下文大小**：调整全局上限，默认 105K；保存后生效，实际窗口仍受模型和模型组上限约束。
- **聊天右上角「⋯」→ 会话通信**：手动发送、查看回复，以及开启本会话的 AI 消息接收权限。
- **聊天菜单 → 任务成果**：查看当前会话文件，预览、分享和刷新。
- **聊天菜单 → 任务预算**：10 / 25 / 50 / 100 / 200 轮，默认 200。这是执行轮数限制，不是金额限额。
- **服务商设置 → 测试调用**：选择模型并点击发送后进行实际文本调用，可取消，可能消耗接口用量。
- **模型列表 → 更多模型与分组**：原有详细选择器、分组管理和模型测试。

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

2026-09-18 的本机 Android 验证：通过项目构建脚本运行 **153 个测试类、1385 项 JVM 单元测试**，0 失败、0 错误、0 跳过，包含 11 项新增会话通信测试；最终菜单图标调整后再次构建 debug APK 成功，差异格式检查通过。

当前 Release 附件的 SHA-256 已与本地构建核对一致：

```text
E28A1FF508AF3057145BE4F533B677DE20C549C68C4D28B6A8DEDD7A56EB8F08
```

历史设备测试记录：分支功能的设备测试曾单独编译通过；整套既有设备测试曾因过期的 `mountedSessionId` 引用无法编译，本轮未重新验证该问题。

这是本地执行记录，不代表 GitHub CI 或真机验收。尚未完成真机界面、跨会话真实模型互通、后台运行及 Keystore 异常验证；开发验证使用模拟接口，没有自动调用用户的付费 API。上下文 Token 预算仍为校准估算，不是精确 tokenizer 计数。

## 更多说明

- [会话通信：手动转发、AI 请求、排队与权限](docs/android-session-communication.md)
- [日常使用增强：八项功能、入口及限制](docs/android-daily-use.md)
- [日常使用实施计划](docs/superpowers/plans/2026-09-14-android-daily-use.md)

- [Astra 与 Image 2.5 接入、生图修复](docs/android-astra-image25.md)
- [任务可靠性与新增入口](docs/android-reliability.md)
- [输入区与思考滑块](docs/android-composer-controls.md)
- [修复实施清单](docs/superpowers/plans/2026-09-13-android-reliability.md)

## 上游与许可

感谢 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 项目及其贡献者。本仓库保留上游历史、版权声明和 **GPL-3.0** 许可；完整条款见 [LICENSE](LICENSE)。本 fork 仅维护 Android 端，iOS 相关介绍请参阅[上游 README](README.upstream.md)。
