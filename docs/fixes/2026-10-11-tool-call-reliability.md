# Android 1.16 同版本修复：工具调用与历史会话

本次保持 versionName 1.16、versionCode 28，使用原 APK 签名。

## 修复内容

- 流式响应必须正常结束且工具参数 JSON 完整后才交付执行，正确识别流内错误；重试和模型回退清理失败尝试，保持调用 ID 与参数、结果一致。
- 统一恢复旧工具记录缺失的外层括号，覆盖聊天、搜索、分支与备份；大消息通过数据库分块读取，保留完整结构和元数据。
- 完整保存并回放 Claude 思考签名，修正 Gemini 空签名和并行工具图片结果顺序；工具 ID 编码避免碰撞。
- 修正 SSE 多行事件、参数快照和上下文卸载，保留图片引用与必要文字，拒绝执行被裁剪的历史参数。
- 修复 Android 打开历史会话时的闪退：历史恢复正则中的字面量右花括号需要显式转义，否则 ICU 编译失败，导致类初始化异常，随后表现为 `NoClassDefFoundError`。桌面 Java 正则引擎接受旧写法，因此 JVM 单测不能覆盖这一差异。

## 验证

- 使用 `scripts/build_android_windows.ps1 -Clean` 完成应用清理构建；`-RunAllTests` 完整通过：2427 项，2425 通过、2 跳过、0 失败、0 错误。
- 本机原生 ICU 引擎复现旧正则的语法错误，修复后编译通过，7 个匹配边界用例通过。
- APK v2 签名、ZIP 完整性、DEX 校验和及 ZIP 对齐通过；检查 APK 内包含修复后的正则常量。
- 44 个品牌/启动图标资源与原 1.16 正式包一致，保留 GPT、DeepSeek、Claude 三套图标。
- APK SHA-256：`f06905743c740e88859c71f7d707e83932a488750a6278f40feb32c44a7bad56`。

## 验证限制

尚未进行真机安装、真实模型接口或长会话压力验证。新增 `LegacyAssistantPartsInstrumentedTest` 覆盖 Android 历史读取入口与旧记录恢复，但完整仪器测试包构建被既有 `ExecutionCoordinatorInstrumentedTest` 阻塞：其仍引用生产代码已经移除的 `mountedSessionId` 字段。没有将这项构建或仪器测试计作通过。

构建脚本的 `-BuildAndroidTests` 只尝试编译仪器测试 APK，不会自动连接手机或执行测试。已有消息曾被旧版本丢弃的原始内容无法通过本次修复重建。
