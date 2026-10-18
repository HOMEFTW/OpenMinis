# GPT Fast 模式与思考面板

2026-10-17 核对官方文档：Priority processing 已更名为 Fast mode。Responses 与 Chat Completions 都支持请求级 `service_tier: "fast"`，`"priority"` 是等价别名。本项目继续使用 `"priority"`，兼容已有 Codex OAuth 和中转接口；开启 Fast 不改动 `reasoning.effort`／`reasoning_effort`。

- [OpenAI API Fast mode](https://developers.openai.com/api/docs/guides/priority-processing)
- [Codex Speed](https://developers.openai.com/codex/speed/)

Responses 请求片段：

```json
{
  "model": "gpt-6.1-sol",
  "service_tier": "priority",
  "reasoning": { "effort": "high" }
}
```

Chat Completions 使用同一个 `service_tier`，思考档位使用原有 `reasoning_effort`。Fast 请求可能增加费用或额度消耗；模型、账户及中转服务需要支持，实际处理档位以服务端响应为准。

输入框的思考档位面板和 `/thinking` 面板增加 Fast 开关；没有思考档位的 GPT 文本模型也可通过 Fast 入口打开面板。聊天菜单入口保留，输入框的闪电标记显示当前选择状态。

沿用现有全局偏好 `codexFastModeEnabled`，新安装默认关闭，已有选择不重置。多个会话使用同一个可观察状态；修改后，下一次请求读取新值。关闭时省略显式 Fast 参数，维持现有默认处理方式。

GPT 开关适用于 OpenAI Chat Completions、Responses 和 Codex OAuth 路由。GPT 图片模型、其他协议及模型名相似的条目不会因此附带 Fast 参数；已有 xAI Priority Processing 保持使用原开关。

## 2026-10-18 可见性修复

将 GPT 的 Fast 控件显示条件与请求可用性分开：在输入框旁的思考档位面板、`/thinking` 面板顶部显示 Fast，之后才显示思考强度。即使当前服务商协议或模型输出能力不满足条件，GPT 控件仍显示为不可用，并提示检查 OpenAI／Responses 接口和文本输出。没有思考档位的 GPT 模型仍可通过 Fast 入口打开面板。

Fast 能力判断沿用模型已有的 `normalizeModalityName()`，修复 `text_output`、`TEXT_OUTPUT` 被误判为非文本输出的问题；显式空输出列表仍不可用，不放宽其他协议、图片模型或相似模型名称的请求限制。覆盖用户反馈的 `gpt-6-astra` 和 `gpt-6.1-sol`。可用性变化不会单独关闭已打开的面板。

## 本地验证

2026-10-18 通过 `scripts/build_android_windows.ps1 -RunAllTests -BuildAndroidTests`：332 个套件、2629 项 JVM 测试，2622 通过、0 失败、0 错误、7 项既有 Windows 安全目录流／POSIX 权限跳过。15 项 Fast 回归与已有 7 项 xAI Priority 回归均通过，覆盖两种请求格式、原生 OAuth、下一次请求切换、思考档位独立、模型／路由边界、共享偏好的保存和重载，以及不可用时仍显示控件和文本输出别名。修复前新增的两项文本输出别名回归确实失败，修复后通过。

应用及仪器测试 APK 构建成功。应用 APK 的 v2 签名、ZIP／DEX 完整性及 16 KiB 页 ZIP 对齐通过，44 个品牌资源保持一致；Fast 请求策略、新的控件显示策略和不可用提示已确认入包。应用仍为 1.16.2／versionCode 30，签名证书 SHA-256 为 `e3536fc5f03bd72e1bc8073b306659cb4edc6b401fb4152048c403c347c08273`。

本轮应用 APK 70,851,702 字节，SHA-256：`7dc6fb080b93c07d9be0ca02a62971d96d525ac5c37edae3b3452ffd31746fc1`。已导出到独立路径 `D:/Code/outputs/openminis-fast-20261018/OpenMinis-1.16.2-fast-fix-20261018.apk`，复制后的散列与构建输出一致，以便与上一轮测试包区分。

没有连接 ADB 设备，未执行真机界面／触控或仪器测试，也未调用真实模型服务验证 Fast 档位与实际加速。上述验证仅覆盖本地构建、测试和安装包检查。
