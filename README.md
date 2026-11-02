# OpenMinis Android 增强版

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://github.com/HOMEFTW/OpenMinis/releases)

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的个人 fork，专注 **Android 模型接入、上下文管理、子代理、会话通信与个性化聊天界面**。

OpenMinis 是原生移动端 AI Agent：连接你自己的模型服务，在手机上使用 Linux 沙箱、文件工作区、浏览器、技能和记忆。本 fork 保留原项目架构，仅增加 Android 端改动；它不是上游官方发行版。

**[下载 Android 1.17.0 APK](https://github.com/HOMEFTW/OpenMinis/releases/download/1.17.0/app-debug.apk)** · [版本说明](https://github.com/HOMEFTW/OpenMinis/releases/tag/1.17.0) · [上游项目说明](README.upstream.md) · [构建指南](docs/android-windows-build.md)

**1.17.0 · 2026-11-02**：新增手机独立运行的 Forge API 2 与无限画布、信息统计、Codeg 技能包、待办清单四个内置插件；新增 Grok 对话背景，随模型切换，并支持全局／会话透明度。对齐上游 **Android 1.15 稳定版（build37）** 的公开源码，补齐网络路由、Tailscale、助手提问恢复、定时任务、语音和更新校验等功能，保留本 fork 的模型接入、会话链接和上下文设置。未纳入上游 1.16 Beta。版本与 APK 验证见 [1.17.0 发布记录](docs/fixes/2026-11-02-release-1.17.0.md)。

**1.16.3 · 2026-10-19，追加会话链接更新**：复制原会话链接后，可在新对话要求“阅读对话 <链接>，帮我……”，分页读取本机原会话并继续任务，也可按链接发送消息。包含 build34 的模型管理、用量趋势、备份服务器编辑、ZIP64、会话草稿、停止范围、语音回退、200% 朗读音量及新增五种语言等更新。自动更新仅连接 [本 fork 的 Releases](https://github.com/HOMEFTW/OpenMinis/releases)，支持稳定／测试渠道。本次 APK 的对应源码见 [追加更新源码 ZIP](https://github.com/HOMEFTW/OpenMinis/releases/download/1.16.3/OpenMinis-1.16.3-session-links-source.zip) 和 [更新与验收记录](docs/fixes/2026-10-19-session-links.md)；原 [1.16.3 标签](https://github.com/HOMEFTW/OpenMinis/tree/1.16.3) 保留首发归档。

**1.16.2 · 2026-10-14**：补齐上游 1.15 beta 的后台提问通知、等待倒计时、MCP／Skills 菜单、朗读音量与长按开关；支持六个会话同时运行，长任务在摘要前逐步回收旧思考及工具步骤。新增手动清理孤立会话文件、Kimi Code 订阅用量和 MiniMax／OpenCode 官方控制台入口，修复 DeepSeek 工具误写进思考时的恢复、列表嵌套表格、转写双击选词、沙箱 Git 配置及 Unicode 输出边界。10 月 18 日补充 GPT Fast 开关及显示修复，对应源码为 [6e49d0d](https://github.com/HOMEFTW/OpenMinis/tree/6e49d0d3feabd5133fb6a29590bc84bfad404bf2)；原同步范围见 [验收记录](docs/fixes/2026-10-14-upstream115-sync.md)。

此前上游 build32／33／34 的 Source code 归档仍是 Android 1.14，因此 1.16.3 曾按发布说明补齐等效行为。本版已改以正式公开的 **1.15 稳定版源码 `6eda5523`** 为同步基线；详情见 [稳定版同步记录](docs/fixes/2026-11-02-stable-115-sync.md)。本 fork 版本号独立递增，不代表上游版本号。

## Forge 插件

1.17.0 已移植 Codex Forge 0.3.2 的 API 2 适配层及四个内置插件，手机独立运行，数据保存在应用私有目录。入口为 **设置 → Forge 插件** 和 **聊天右上角菜单 → Forge 插件**。可管理内置插件的启用／停用，停用保留数据；暂不支持第三方插件安装、卸载、更新或插件市场。

| 插件 | 功能 |
| --- | --- |
| 无限画布 | 文字、便签、图片、文件、分组和连线；自动保存、撤销重做、删除恢复，支持 AI 协同编辑。 |
| 信息统计 | 本机会话用量、请求速度、上下文占用和模型设置变化；支持粘贴数字样本分析及手动触发的三次隔离检测。 |
| Codeg 技能包 | 四组 37 项技能和完整参考资源；默认停用，支持全局启用、会话覆盖及恢复继承。启用后 AI 可按需读取。 |
| 待办清单 | 个人／当前会话清单、四列状态、备注、排序、计划时间、变更记录和删除恢复，支持 AI 读写。 |

例如对 AI 说“在无限画布整理这个方案”“把这三项加入当前会话待办”。技能正文通过 `forge` 工具读取。待办的计划时间用于记录安排，不自动启动 AI；模型检测由用户主动触发并消耗模型额度，检测结论仅供参考。技能启用不会安装额外依赖。

API 2 按 Android 宿主实际能力提供权限检查、插件存储、草稿读写、面板和事件。桌面 Node/Electron、桌面插件安装和电脑数据同步不在本次移植范围。Forge 数据目前不包含在原有自定义备份导出中。实现与验证见 [接入记录](docs/fixes/2026-11-02-forge-android.md)。

## 应用品牌图标

默认使用 **GPT 龙娘**。在 **设置 → 外观** 中可切换 GPT、DeepSeek、Claude 三套图标，桌面入口、关于页、最近任务、通知和悬浮入口使用对应品牌素材。图标选择独立于随模型切换的对话背景；已固定的旧网页快捷方式不会自动替换图标。

| GPT（默认） | DeepSeek | Claude |
| --- | --- | --- |
| <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" alt="GPT 龙娘图标"> | <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground_light.png" width="100" alt="DeepSeek 图标"> | <img src="src/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground_dark.png" width="100" alt="Claude 图标"> |

[品牌素材来源](src/android/app/src/main/assets/brand_icons/SOURCES.txt)

## AI 对话背景

DeepSeek、GPT、Claude、Grok 模型自动使用对应背景；对话中切换模型或自动回退时同步切换。图片在输入框上方完整缩放显示，避免人物脚部被遮挡。设置页以两列展示四款背景预览；Grok 形象已包含在 1.17.0 中。

<table>
  <tr><th>DeepSeek</th><th>GPT</th><th>Claude</th><th>Grok</th></tr>
  <tr>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_deepseek.webp" width="220" alt="DeepSeek 鲸鱼娘背景素材"></td>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_gpt.webp" width="220" alt="GPT 白发龙娘背景素材"></td>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_claude.webp" width="220" alt="Claude 橙发书卷娘背景素材"></td>
    <td><img src="src/android/app/src/main/res/drawable-nodpi/chat_wallpaper_grok.webp" width="220" alt="Grok 金发双马尾背景素材"></td>
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
| Forge 插件 | 手机本地运行无限画布、信息统计、37 项 Codeg 技能和待办清单；支持内置插件启停及技能全局／会话覆盖，模型按权限读写。 |
| AI 对话背景 | GPT、DeepSeek、Claude、Grok 四款背景随实际模型自动切换，支持会话开关、全局默认透明度和会话独立透明度。 |
| 网络路由 | 新增 Tailscale、代理和按服务商／用途配置的路由，覆盖模型、Shell、WebView 与备份；路由不可用时阻断，避免静默切回直连。 |
| 模型接入 | 支持 GPT-6 Astra / Sol / Luna、GPT-6.1 Sol 及 Image 2.5；思考参数按模型支持范围发送，GPT-6.1 Sol 默认 high。 |
| 模型管理与 Fast | 服务商列表可折叠隐藏模型、删除 API 模型，自动刷新不恢复删除项；优先采用服务端模型限制。输入框思考档位面板及 `/thinking` 提供 GPT Fast 开关，与 `chat.speedTier` 共用状态，可用档位受模型及服务商能力限制。 |
| DeepSeek 直连 | 独立 DeepSeek 服务商与 API Key 入口，依据官方 Harness 接口实现请求、思考回放、工具调用和流式处理，不经过 OpenAIProvider 或 AnthropicProvider。 |
| 子代理与调度 | 子代理定义、模型选择、父子会话、后台进度、引导/停止/恢复及结果回调；定时任务支持即时执行、倒计时和忙碌会话投递。 |
| 后台问答 | 模型提问可在后台通知中逐题回答，支持选项、允许／取消和文字回复；等待状态显示倒计时，过期或旧通知不能回答新问题。 |
| 会话工具菜单 | MCP 支持搜索、对过滤结果批量启停和服务器详情；Skills 可查看正文，长按网址来源技能复制原始来源地址。 |
| 服务商用量 | Kimi Code 登录后显示服务端订阅额度与刷新状态，独立于本地 Token 统计；MiniMax／OpenCode 服务商详情可打开官方控制台。 |
| 用量趋势 | 本地统计支持今天／7 天／30 天／全部时间、缓存 Token 堆叠趋势及服务商／模型下钻，按设备本地日历日计量。 |
| 备份恢复 | 可直接编辑备份服务器，保存失败回滚配置与凭据；历史文件原子写入，支持 ZIP64。恢复优先采用备份设置并保留设备独有项，有效 OAuth 不被过期备份替换。 |
| 自动更新 | 手动及自动检查均连接 HOMEFTW/OpenMinis，支持稳定／测试渠道和主页更新提示；优先当前应用 APK，排除备份与仪器测试包。 |
| 存储维护 | 扫描并确认后手动清理孤立会话目录；保护已有会话、媒体引用、草稿、迁移及运行中的任务和终端，清理前重新核验，不在启动时自动删除。 |
| 朗读与交互 | 朗读胶囊上方提供最高 200% 的独立音量滑块；长按工具状态栏开关预览，长按思考块切换流式展开；停止按钮支持长按设置和停止范围选择。 |
| 输入区 | 模型与思考强度入口移入输入框，使用可搜索模型列表和分档滑块；保留模型分组、当前项勾选及详细选择器。 |
| 上下文管理 | 独立设置页提供 64K / 105K / 272K / 1M 预设及 8K–1M 自定义上限；结合全局、模型与模型组限制，按服务端输入用量校准预算，计入 Anthropic 缓存 Token。 |
| 会话通信与链接 | 可复制会话深度链接，在新对话按链接分页读取原会话并继续任务，或按链接发信；支持手动转发、AI 请求、接收授权、持久化排队、关联回复及防循环，不自动合并历史或复制附件。 |
| 生图结果 | 修复已返回图片却只保存提示词的问题。JSON 输出包含 `media_files` 清单，多图全部保存；无图片、下载失败及写入失败明确报错。 |
| 图片预算 | 单图 5 MiB、单次请求图片预算 100 MiB；校验编码与 MIME，按需加载历史图片，超预算时保留原附件。服务商自身限制仍适用。 |
| 会话隔离 | 输入 JSON、系统文件、参考图及输出按调用会话解析，避免并行任务读写其他会话目录。 |
| 任务结果 | 区分本次运行的成功、失败、取消、超时和预算停止；排队批次分别记录，定时任务失败也写入历史。 |
| 凭据与日志 | 加密存储失败时保留原数据，使用有提示的进程内临时存储；日志和分享副本脱敏。 |
| 终端显示 | 修复回车覆盖、ANSI 和 OSC 序列处理，保留合法 `null`、空白和文本。 |
| 启动与草稿 | 修复重复会话列表，恢复最后查看的对话；持久保存已有会话草稿并提供草稿箱，新会话未发送文本保留 15 分钟。大会话首批列表就绪后淡入，阅读旧回复时保持位置。 |
| 数据恢复与分享 | 恢复已有会话时保留消息和压缩标记，旧备份不覆盖较新正文；备份包含嵌套媒体引用，副本独立保存资源，分享在草稿保存成功后才确认消费。 |
| 工具可靠性 | MCP 副作用结果丢失时报告结果未知，避免自动重复执行；正确处理 SSE 事件。同文件编辑与写入协调，大文件窗口读取限制内存使用，过期浏览器/终端回调不影响新请求。 |
| 调度与配置 | 并发准入、排队和取消避免名额泄漏；定时任务队列保活到接管或终态。后续请求刷新凭据与服务商配置，统一执行条目思考上限。 |
| 上游同步 | 对齐上游 Android 1.15 稳定版源码，补齐模型／工具协议、助手提问恢复、500 轮上限、任务收尾、网络、语音、存储和更新身份校验，保留本 fork 的扩展。详见 [同步记录](docs/fixes/2026-11-02-stable-115-sync.md)。 |
| 日常使用 | 新增任务中心、跨会话作品库、保留原历史的编辑分支、分类错误建议、Markdown 导出和备份预览。 |
| 任务工具 | 新增任务成果列表、轮数预算、上限及重试提醒，以及服务商实际调用自检。 |
| 长对话稳定性 | 摘要前按最旧顺序回收无签名思考及完整工具步骤，保留回读文件、原数据库历史和最新四条；限制 Markdown 解析、嵌套与缓存，超长内容支持分块全文查看和导出。图片发送同时检查文件大小与像素尺寸。 |
| 标题与反馈 | 自动标题严格解析并限制等待时间，不覆盖手动改名；关于页与反馈统一指向本 fork，反馈仅保留 GitHub Issues。 |
| 超时恢复 | 子代理超时后可恢复原子会话，等待旧任务收尾，避免新旧运行互相覆盖。 |
| DeepSeek 恢复 | 识别误写进思考内容的工具调用标记，最多请求两次正式工具调用，失败后提供继续入口；不直接执行思考文本，保留输出上限及断流提示。 |
| 语音转写 | `android-speech` 支持麦克风或授权目录中的音频文件，默认系统引擎，也可显式选服务商或自动回退；调用进程退出后停止，上传前重新校验进程身份，纠错最多三个模型按策略回退。 |
| 端点证书 | 为指定 HTTPS 主机与端口配置 PEM CA，保留证书链和主机名校验；可清除恢复默认信任。 |
| 记忆与兼容性 | 近期记忆日志可独立关闭；统一 Claude 认证与聊天请求头；识别特定 PRoot 断言后，对后续 Shell 使用兼容模式，不自动重放命令。 |

## 安装与使用

1. 从 [Releases](https://github.com/HOMEFTW/OpenMinis/releases) 下载 APK。当前构建面向 **Android 8.0+、ARM64**，使用调试签名；不是上游官方发行版，尚未完成真机验收。
2. 在服务商设置中填写自己的接口地址和凭据，刷新模型列表。可用模型取决于服务商和账号权限；项目不附带 API Key 或模型额度。
3. 输入框内点击模型名切换模型，点击思考强度打开滑块。滑块只提供当前模型允许的档位；GPT 的 Fast 开关也位于该面板，下一次请求生效，可用性与费用取决于服务商。
4. 图片模型通常作为 Agent 工具使用：在模型分组设置中开放给 Agent，再让文本模型调用 `minis-model-use`。

DeepSeek 官方 Key：添加服务商时选择 **DeepSeek** 并填写密钥。旧兼容服务商配置保持不变，不会自动迁移。此入口使用官方 Harness 同款 `https://api.deepseek.com/anthropic/v1/messages` 与 `x-api-key`，由独立 DeepSeek 实现处理；不是另造私有协议。详见 [接入与验证记录](docs/superpowers/plans/2026-10-08-deepseek-native.md)。

新增入口：

- **会话列表右上角菜单 → 草稿箱 / 任务中心 / 作品库**：找回草稿、管理本次进程任务和浏览跨会话文件。
- **长按用户消息 → 在新分支中编辑**：保留原会话和回答，附件独立复制。
- **长按会话 → 导出 → Markdown**；**备份 → 开始备份**前展示范围、凭据类别及大小估算。
- **设置 → 上下文大小**：调整全局上限，默认 105K；保存后生效，实际窗口仍受模型和模型组上限约束。
- **设置 / 聊天右上角菜单 → Forge 插件**：打开无限画布、信息统计、Codeg 技能包和待办清单，管理内置插件启停。
- **设置 → 网络**：配置 Tailscale、代理及按服务商／用途划分的网络路由。
- **聊天右上角「⋯」→ 会话通信**：手动发送、查看回复，以及开启本会话的 AI 消息接收权限。
- **聊天菜单 → 任务成果**：查看当前会话文件，预览、分享和刷新。
- **聊天菜单 → 任务预算**：设置执行轮数，默认 200、上限 500；这是执行轮数限制，不是金额限额。
- **服务商设置 → 测试调用**：选择模型并点击发送后进行实际文本调用，可取消，可能消耗接口用量。
- **设置 → Kimi Code 服务商详情 → 订阅用量**：使用登录凭据读取服务端额度，可手动刷新；MiniMax／OpenCode 的官方接口服务商详情提供官方控制台入口。
- **设置 → 服务商详情 → 模型列表**：展开隐藏模型，或删除 API 模型；自动刷新不会恢复删除项，手动刷新模型列表可重新获取。
- **设置 → Token 用量**：切换时间范围，查看缓存趋势及服务商／模型明细。
- **设置 → 备份与恢复 → 管理目的地**：编辑已有服务器名称、路径和连接；ZIP64 备份在其他平台恢复时需要对端支持。
- **设置 → 关于 Minis → 应用更新**：调整自动检查及稳定／测试渠道，只从本 fork 获取更新。
- **设置 → 存储 → 清理孤立会话文件**：先扫描，再确认清理；仍被引用或正在使用的文件会保留。
- **模型列表 → 更多模型与分组**：原有详细选择器、分组管理和模型测试。
- **设置 → 服务商详情 → 自定义服务器证书**：粘贴 PEM CA，仅对配置的 HTTPS 主机与端口生效；同端点账号共享配置，留空可移除。
- **设置 → 记忆 → 自动加入近期记忆日志**：默认开启，关闭后从下一轮生效，不影响全局记忆和记忆工具。
- **设置 → 反馈问题**：[本 fork 的 GitHub Issues](https://github.com/HOMEFTW/OpenMinis/issues)。

停止按钮可长按调整行为；存在插入任务或子任务时可选择停止范围。`minis-config` 可管理新增偏好和子代理列表，定时任务创建支持 `--model` 指定模型。

音频工具示例：`android-speech transcribe --source /var/minis/workspace/audio.wav --engine provider`。`--source mic` 使用麦克风，`--engine auto|system|provider` 选择引擎；文件须位于授权目录，最多 25 MiB、300 秒。系统文件转写依赖 Android 13+ 及设备识别服务支持。

不同签名的官方版或第三方 APK 不保证能够覆盖安装，请先通过应用备份功能保留数据。本 fork 的同签名测试版可尝试直接更新。

## 会话通信怎么用

会话链接已加入 1.16.3 追加更新：在原会话「⋯ → 复制会话链接」，或在会话列表长按后复制；新对话输入“阅读对话 `<复制的链接>`，帮我……”即可让 AI 分页读取原会话并在当前对话继续处理。仅引用当前安装中的本地历史，读取不会唤醒原会话模型或自动共享附件；按链接发信仍需目标开启 AI 消息接收。请使用上方最新 APK，详见 [实施与验证记录](docs/fixes/2026-10-19-session-links.md)。

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

2026-11-02，1.17.0 使用 `scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests` 完成全量验证：**503 个测试套件、3,846 项 JVM 测试，3,838 通过、8 项条件跳过、0 失败、0 错误**。跳过项为 Windows 不支持的 POSIX 权限／SecureDirectoryStream 条件及未启用的 4 GiB 大文件用例。应用和 Android 仪器测试 APK 均编译成功；仪器测试未在设备上执行。

APK v2 签名、ZIP CRC、重复条目、16 KiB 原生库／4 字节 ZIP 对齐、包名与版本检查通过。签名证书与已发布 1.16.3 一致。四个 Forge 插件、283 个技能清单文件、前端资源和 Grok 背景均已核对入包；技能原始 SHA-256 与 Windows 重新检出的文件字节一致。

安装包 SHA-256 见 [1.17.0 校验文件](https://github.com/HOMEFTW/OpenMinis/releases/download/1.17.0/app-debug.apk.sha256)。详细产物、范围与限制见 [1.17.0 发布记录](docs/fixes/2026-11-02-release-1.17.0.md)。源码与 APK 对应同一 `1.17.0` 标签，GitHub 的 Source code 归档包含本次改动；原生子模块仍按构建指南初始化。

Forge 前序验证还包含 19 项前端行为回归和 360 CSS px 手机尺寸浏览器检查，使用隔离存储与合成数据，详情见 [接入记录](docs/fixes/2026-11-02-forge-android.md)。这不替代 Android WebView 和真实模型服务验收。

当前没有连接 ADB 设备，未执行真机安装、触控、通知回复、后台 Doze、Tailscale／代理实际网络、外部语音、真实模型或备份服务器联调。本地编译和 JVM 测试不代表这些场景已经验证。Forge 数据暂未纳入原有自定义备份导出；待办计划时间不会自动启动 AI，技能启用不安装额外依赖，模型指纹检测仅供参考。

上下文 Token 预算仍为校准估算，不是精确 tokenizer 计数；Markdown 页内锚点暂不定位标题。16 KiB ZIP 对齐通过不代表所有原生库已在 16 KiB 页面设备上运行；iOS 不在本 fork 的同步范围。

## 更多说明

- [1.17.0 发布与验收记录](docs/fixes/2026-11-02-release-1.17.0.md)
- [上游 Android 1.15 稳定版同步](docs/fixes/2026-11-02-stable-115-sync.md)
- [Forge API 2 与四个内置插件](docs/fixes/2026-11-02-forge-android.md)
- [2026-10-19 会话链接与 1.16.3 追加更新记录](docs/fixes/2026-10-19-session-links.md)
- [2026-10-19 Android build34 同步与 1.16.3 验收记录](docs/fixes/2026-10-19-build34-sync.md)
- [GPT Fast 模式与显示修复记录](docs/fixes/2026-10-17-gpt-fast-mode.md)
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
