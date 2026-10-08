# DeepSeek API Key 直连

依据：deepseek-ai/deepseek-harness `5badb15009ae1756c3afe0ae0cef1faafc290ccc`。
官方 `llm-deepseek-api-key` 使用 `x-api-key`，直连
`https://api.deepseek.com/anthropic/v1/messages`，是 Messages 协议的 DeepSeek 子集，
不是 OpenAI Chat Completions，也不存在源码支持的另一种私有消息格式。

1. 增加独立 DeepSeek 提供商、API Key 入口及官方静态模型目录，保持旧配置不变。
2. 独立实现 Messages 请求与 SSE：思考 off/low/high/max、工具回放、图片、累计用量、
   错误、取消和无进展超时；不自动回退其他协议，不继承 Claude OAuth/cache 参数。
3. 保存思考回放信息，覆盖真实 HTTP 发包和流式异常的回归测试。
4. 使用 `scripts/build_android_windows.ps1 -RunAllTests` 验证并打包，不提交或推送。

验收：新增入口不要求选择兼容协议；工厂使用独立 DeepSeekProvider；请求地址和认证
符合官方实现；工具多轮及思考档位稳定；HTTP/流式失败不能被当作成功结束；
不降低已有图片预算，不破坏其他提供商。无真实密钥时只报告本地协议验证。

范围：Android API Key 对话接入；不引入 Harness 运行时、账号登录、云端文件管理或遥测。

## 实施记录

- 已增加 `ProviderType.deepSeek`、中英文入口、官方推荐模型目录及独立工厂分支。
- 使用独立 `DeepSeekProvider` / `DeepSeekMessages` / `DeepSeekStream`，禁用重定向，
  不使用 OpenAI 或 AnthropicProvider；共享项目图片预算和取消/无进展保护。
- 原生思考签名和块顺序通过 `deepSeekReplay` 保存到消息 parts，不修改数据库结构。
  元数据只保存长度、哈希、签名；不复制回复正文。模型或历史不匹配时丢弃签名。
- 工具完成事件仅在完整 `message_stop` 后发出；无终止事件、错误或无效工具 JSON
  不会被当作成功工具调用。用量合并初始值与后续增量中的累计字段。
- 配置代理 `01a11ac9-8855-7dc3-98c8-a03dc8a389a4` 已停止写入，主代理核对实际文件后接手。
  已补齐首次编译发现的设置页枚举分支遗漏。配置的 JSON/数据库往返有行为测试，
  工厂通过 MockWebServer 实际发包验证，不依赖源码字符串断言。
- 旧兼容服务商不自动迁移；要使用新通道，添加 DeepSeek 服务商并填入官方 API Key。
- 独立只读审查发现并修复两个工具边界：`max_tokens` 时丢弃全部工具调用及预览/回放，
  严格解析完整工具 JSON，拒绝尾随垃圾。补充了完整/残缺参数截断及历史继续回放测试。

## 最终验证

- `scripts/build_android_windows.ps1 -RunAllTests`：成功。
- Android 单元测试共 2280 项：2278 通过，0 失败，0 错误，2 跳过。
  跳过的是既有 SessionStorageTest 的两个 POSIX 目录权限测试（Windows 不支持）。
  本轮新增 20 项行为回归测试全部通过。
- APK：`src/android/app/build/outputs/apk/debug/app-debug.apk`，70,303,967 字节。
- SHA256：`8460ee9008c218492dbff2aeddd0ab9268b366967ca5e299575bccf1fcea2abe`。
- APK v2 签名验证通过；本轮相关已跟踪文件 `git diff --check` 通过。
- 未使用真实 DeepSeek 密钥联调，也未做真机交互验证；本地验证为协议发包、流式状态、
  配置往返、回放、取消、超时及错误处理。没有提交或推送，两个本轮子代理均已关闭。
