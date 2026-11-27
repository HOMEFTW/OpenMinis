# 点击发送时报 Anthropic 配置错误

## 原因与修复

用户截图报错为 `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured`。
`ChatViewModel.systemPromptFor` 在判断当前服务商之前，无条件调用了
`ClaudeOAuthManager` 的必填配置 getter。公开构建中该配置为空，因此所有模型
进入 agent loop 时都会在发送 HTTP 请求之前失败；重试和模型回退也调用同一函数。

将这段无状态逻辑提取至 `ui/chat/ProviderSystemPrompt.kt`：仅 Claude OAuth
读取必填配置，其他路由使用可为空的构建值。已有前缀的回退清理逻辑保留。
其余五处聊天入口及 Anthropic 请求构造中的必填配置读取均已有 OAuth 判断。
版本保持 1.17.0（32），保留原有未提交修改。

## 验证

- 修复前：7 条路由、21 项回归测试全部出现与截图一致的配置异常，保存于
  `D:/Code/.tools/openminis-send-fix-20261127/regression-before.xml`。
- 修复后：7 套件、126 项定向测试最终全部通过，无跳过。覆盖提示词、空提示词、
  重试/回退、Claude OAuth 配置检查，以及 OpenAI、Anthropic、Gemini、DeepSeek 请求。
- 新增 6 项本地 MockWebServer 请求验证，确认提示词处理后请求到达接口并收到回复。
  首轮 126 项中有 1 项测试夹具误向 GPT-6 的 Responses 请求返回 Chat Completions 数据；
  修正夹具后重跑该套件，6 项通过。上述总数合并未受影响结果与最终复测结果。
- 构建均使用 `scripts/build_android_windows.ps1`。最终 APK 的 ZIP CRC、重复条目、
  v2 签名、16 KiB 原生库/4 字节 ZIP 对齐及修复类入包检查通过。
- Forge 已验证页面资源与上次本地修复包逐字节相同；当前 SDK 等入包资源与源码一致。
- 未连接 Android 设备；未执行真机安装或真实服务商/OAuth 联调。

## 本地安装包

`D:/Code/.tools/openminis-send-fix-20261127/OpenMinis-1.17.0-chat-fix.apk`

- 包名：`com.openminis.app`；Android 8.0+；ARM64；78,900,323 字节。
- SHA-256：`8bb20967a351f02417fb91ef894f76aa2509ba5cda9a55c91b940aa6f31765ea`。
- 签名证书 SHA-256：`e3536fc5f03bd72e1bc8073b306659cb4edc6b401fb4152048c403c347c08273`，与上次安装包一致。
- 原始日志、测试报告和 `verification.json` 位于安装包同目录。

本轮未提交、未推送或替换 GitHub Release。
