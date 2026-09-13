# Android：GPT-6 Astra 与 GPT Image 2.5

本次仅修改 Android。

## 使用

- OpenAI API Key 内置列表和 Codex OAuth 列表新增 `gpt-6-astra`、`gpt-image-2.5-flare`、`gpt-image-2.5-sunburst`。
- 已配置的供应商请刷新模型列表。API Key 模式仍以服务端 `/models` 返回的可用模型为准，也可通过原有自定义模型入口添加上述 ID。
- Astra 可选择 low、medium、high、xhigh、max。模型始终进行推理，原有“关闭思考”设置按 low 发送；已有 ultra 设置按项目原规则映射为 max。
- Astra 默认使用 Responses API，以支持代理工具调用；不自动发送 temperature。
- Image 2.5 可作为原有 `minis-model-use` 生图/编辑模型使用。API Key 模式沿用 `/images/generations` 和 `/images/edits`，明确传递所选模型 ID，不添加不受支持的 `response_format`。
- Image API 的 quality 可填写 auto、low、medium、high、xhigh、max。例如：`{"prompt":"一只红熊猫宇航员","size":"1024x1024","quality":"high","n":1}`。
- Codex OAuth 模式沿用 Codex Responses 生图通路：主模型使用 Astra，`image_generation` 工具明确指定 Flare 或 Sunburst，并保留参考图片。原有 GPT Image 2 请求形状保持不变。

## 验证和限制

新增 `AstraImage25Test`，覆盖静态列表、服务端模型列表、快照 ID、五档思考参数、OFF/ULTRA 兼容、默认 Responses 路由、图片生成/编辑请求和 OAuth 工具模型选择。

完整 Android 单测入口：

```sh
cd src/android
./gradlew :app:testDebugUnitTest --tests com.openminis.app.provider.AstraImage25Test
```

首次构建因缺少 `app/libs/rclone.aar` 失败；随后已在 Windows 本机从源码编译真实的 rclone 和 PRoot、准备 Alpine，并完成 APK 构建。完整 Android 工程中的 AstraImage25Test 共 7 项通过，失败/错误/跳过均为 0。后续打包入口见 [Windows 构建说明](android-windows-build.md)。

为独立验证本次逻辑，使用临时 JVM 测试工程编译实际模型、模型目录、思考解析器和 OpenAI provider 源码，运行新增测试及原有 OpenAIEditImageTest、ResponsesTopLevelImageTest，共 19 项通过。`git diff --check` 也通过。测试隔离了 Android 日志、设备信息、诊断、图片压缩等非目标依赖，网络请求由 MockWebServer 接收；结果不能替代完整 Android 构建、真机验证或真实接口调用。

没有使用真实 API Key/OAuth 账号调用模型。实际模型权限，以及 Codex 后端是否接受指定 Image 2.5 工具模型，仍需使用有权限的账号验证；失败会沿用现有错误处理，不会静默替换为旧图像模型。OAuth 通路沿用原有自动图片参数，Image API 的 size/quality/n 不适用于该通路。

## Android 生图结果保存修复（2026-09-13）

- 根因：指定 `--output result.json` 时，旧代码只写入 `response.text`，忽略已经收到的图片；直接输出图片文件时也会丢失第二张及后续图片。
- 修复：所有图片都会保存，返回 `media_files` 路径。JSON 输出保存完整结果清单，图片输出保存第一张并保留其余附件，文本输出保留提示词并另存图片。无图片、路径无法解析、写入失败均返回失败。
- Image 2.5 的 Images API 默认参数为 `size: "1024x1024"`、`quality: "high"`、`n: 1`，显式输入可以覆盖。提示词按当次任务填写，不固定为示例人物。

默认输入格式（JSON 字符串内换行需写为 `\n`）：

```json
{
  "prompt": "在这里填写本次图片提示词",
  "size": "1024x1024",
  "quality": "high",
  "n": 1
}
```

`ModelUseImageResultTest` 覆盖 JSON/文本/图片输出、多图、自动保存、无图片、路径与写入失败，以及模拟 Images API 响应经过下载后写入 JSON 清单的流程。本轮完整 Android 工程的 4 个相关测试类共 27 项通过（0 失败、0 错误、0 跳过），APK 构建、ZIP/原生依赖检查和 v2 签名验证通过。未调用付费接口，安装后的实际生图仍需真机验证。

## 官方依据

- https://developers.openai.com/api/docs/models/gpt-6-astra
- https://developers.openai.com/api/docs/guides/latest-model
- https://developers.openai.com/api/docs/models/gpt-image-2.5-flare
- https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst
- https://developers.openai.com/api/docs/guides/image-generation
