# Android 上下文设置与图片预算修复计划

## 目标

在保留现有上下文设置功能的基础上，修复设置真正进入请求链路后暴露的边界问题：请求不得超过有效上下文窗口，图片不得绕过字节预算，图片被压缩或淘汰时附件元数据必须保持对应关系。计划只覆盖 Android 相关代码；不恢复或修改已批准删除的 `src/ios`、`deps/build_ffmpeg.sh`、`deps/build_ish.sh` 和 `deps/build_rclone_ios.sh`。

## 当前实现与风险

1. `ContextWindowSettings`、快捷选项、SharedPreferences 持久化和有效窗口取最小值的基础实现已存在，重点转为验证和修复其下游使用。
2. `prepareUserAttachments()` 对图片预算结果使用 `mapIndexed` 加尾部裁剪，若中间图片被预算淘汰，后续图片会绑定错误的 URI、文件名、媒体引用、Linux 路径或 MIME。
3. 图片解码/编码失败时 `compressBytes()` 返回原始数据；压缩阶梯耗尽后仍可能返回超过 5MB 的图片，导致超限图片继续发送。
4. `applyRequestImageBudget()` 只扫描 `contentParts`，而 `VisionGroupResolver`、`ModelUseOffloadHandler` 等路径通过独立的顶层 `imageParts` 调用 provider，可能绕过请求级 25MB 图片预算。
5. 请求预算使用 `System.identityHashCode(ByteArray)` 标识图片，同一数组在多个位置重复出现时无法区分各次出现，淘汰和占位符替换可能落到错误位置。
6. Token 用量页的 Max Output 当前显示模型声明值，没有反映用户设置和模型/模型组限制共同形成的有效窗口，可能给出过大的可用输出暗示。

## 修复步骤

### 1. 先锁定现有设置契约

- 保留 `ContextWindowSettings` 的默认值 `105000`、快捷值 `64000/105000/272000/1000000` 和自定义范围 `8000..1000000`。
- 确认 UI 草稿值与已保存值分离，非法输入不会写入；恢复默认值、进程重启读取和设置变更后的实时显示保持一致。
- 复用现有 `effectiveContextWindowTokens()`，确保模型声明窗口、模型组限制和全局设置取最小的正值。
- 以现有 `ContextWindowSettingsTest`、`ContextBudgetTest` 为基础补齐缺口，不为了设置功能引入新的持久化或状态层。

### 2. 修复单条消息图片预算和附件元数据

涉及：

- `src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- `src/android/app/src/test/java/com/openminis/app/provider/ImageBudgetTest.kt`

要求：

- 把图片数据与 URI、文件名、媒体引用、上传路径、MIME 等元数据作为同一个 occurrence 处理；预算删除哪一次图片，就同步删除同一次的全部元数据，不使用“保留前 N 项”的尾部裁剪推断。
- 压缩后若字节仍超过 `MAX_PER_IMAGE_BYTES`，或图片无法解码/编码，必须将该 occurrence 淘汰或转换为已有的可恢复路径占位符，不能把超出 5MB 的原始字节交给 provider。
- 压缩成功时同步使用 JPEG MIME；未压缩的图片保持原始 MIME 和全部关联元数据。
- 保持图片顺序、非图片附件顺序、持久化 `mediaRefPartsJson` 和用户气泡展示顺序一致。
- 统计 `compressedCount`、`droppedCount`、`totalBytes` 的含义与实际结果一致，并保留现有 Snackbar/事件提示行为。

### 3. 统一请求级图片预算入口

涉及：

- `ImageBudget` 的请求级规划模型和测试
- `ChatViewModel` 的请求组装/重试/工具循环路径
- `src/android/app/src/main/java/com/openminis/app/tools/VisionGroupResolver.kt`
- `src/android/app/src/main/java/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt`
- 必要时调整 provider 公共边界或增加小型共享 helper，但不重构 provider 请求格式

要求：

- 请求级图片预算固定使用字节单位和现有 25MB 传输上限；不要把上下文 Token 数直接作为字节数，也不要因上下文设置变化而制造单位混用。
- 每个实际会进入 provider payload 的图片 occurrence 都必须被规划，包括 `LLMMessage.contentParts` 中的图片和独立 `imageParts` 参数；同一请求中不可存在绕过规划的直通路径。
- 用稳定的 occurrence ID（例如收集顺序/结构位置组成的 ID）标识图片，重复引用同一 `ByteArray` 时也必须能分别决定保留或淘汰。
- 淘汰时只替换对应 occurrence；保留最近用户输入和最近工具结果的既有优先级。无 Linux 路径时继续使用 spillover，失败则生成明确的无路径占位符。
- 检查普通发送、首次请求、重试/fallback、工具循环、Vision Group 和 model-use 的实际调用点，确保预算后的消息与顶层图片参数共同传给 provider。

### 4. 修正上下文输出上限展示与动态输出保护

涉及：

- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/TokenUsageSheet.kt`
- `src/android/app/src/test/java/com/openminis/app/ui/chat/ContextBudgetTest.kt`

要求：

- Max Output 显示当前有效上下文窗口约束下的输出上限：模型声明的输出上限与有效上下文窗口取较小值；不得继续无条件显示模型声明值。
- 动态 `maxTokens` 保证估算输入 Token 加输出 Token 不超过有效窗口；剩余空间不足时走现有 compact/offload/停止提示分支，不能用固定最小值强行超额发送。
- 发送前估算必须包含 system prompt、工具定义、实际历史、当前附件/工具结果；每次工具循环和重试都基于即将发送的 payload 重算。
- 保留模型未声明上下文或输出上限时的既有 provider fallback 规则，不改变正常文本、工具和生图请求的业务语义。

### 5. 回归测试与验证

补充或调整针对性 JVM 测试，至少覆盖：

- 中间图片被淘汰时，后续图片的数据与 URI、名称、路径、MIME 仍一一对应。
- 单张 6MB 图片压缩成功时最终不超过 5MB；无法解码或压缩阶梯耗尽时不发送超限原始字节。
- `contentParts` 与顶层 `imageParts` 混合后累计超过 25MB 时会淘汰旧图片；重复引用同一 `ByteArray` 时按 occurrence 独立处理。
- 无图片、恰好达到预算、只超出一个 occurrence、spillover 失败和路径占位符。
- 64K/105K/272K/1M 有效窗口、模型/组限制取最小值以及动态输出不得超过剩余窗口。
- Token 用量页使用有效窗口约束后的 Max Output 值。

验证顺序：

1. 先运行相关 JVM 测试，确认设置、图片预算和动态输出回归测试通过。
2. 使用项目构建脚本执行 `.\scripts\build_android_windows.ps1 -RunAllTests`；必要时再执行不带 `-RunAllTests` 的构建脚本确认 APK 编译链路。
3. 执行 `git diff --check`，并复查变更只涉及本计划范围，保留用户已批准的删除和其他不相关改动。
4. 若构建仍受 Windows NDK `AccessDeniedException` 或 Gradle 报告目录权限限制，只报告已完成的编译/测试阶段和确切环境限制，不声称 APK 或完整测试通过。

## 验收标准

- 默认上下文设置为 105K，快捷值、自定义边界、重启持久化和非法输入行为正确。
- 所有普通聊天、工具循环、重试、Vision Group 和 model-use 图片入口都遵守单图 5MB、请求总量 25MB 的字节预算。
- 图片压缩、淘汰或占位符替换不会错配任何附件元数据，也不会发送已知超限原始字节。
- 有效上下文窗口由模型、模型组和用户设置共同约束；输入与输出总量不超窗，Max Output 展示不误导。
- 相关 JVM 测试和构建脚本的实际结果被如实记录；不提交代码，不恢复已批准删除。
