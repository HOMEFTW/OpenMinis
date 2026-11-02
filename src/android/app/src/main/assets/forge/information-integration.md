# Android 信息统计接入合同

服务：`ForgeInformationService(context: Context, app: MinisApp)`。

```kotlin
suspend fun request(method: String, args: JSONObject, sessionId: String?): Any?
```

Web API 2 调用：`api.request('statistics', { method, args, threadId })`。内部 method 为
`snapshot`、`analyze`、`probe`、`cancel`、`import`。`sessionId` 只能来自 host 的 native 绑定；
顶层和内层 `threadId` 如存在，必须都等于可信 sessionId。网页传入的模型、服务商、档位和分析结论均不采用。

`sessionId=null` 的 snapshot 返回全部本机会话的实际用量，`threadId:null`、`scope:'global'`；
其他操作需要已保存的会话。全局入口不复用任何会话的检测结果，不运行探测。

## 主代理接入点

collector 是独立单例：`ForgeInformationCollector.get(context)`。

1. `ChatViewModel.syncForgeInformationSelection()`：通过 `providerForNextRequest()`、真实 entry、
   thinking 和 `effectiveContextWindowTokens(provider.model)` 调用 `bind`，用户显式设置路径传
   `settingsApplied=true`。`ForgeHost` 在每次用户 statistics 请求前调用它，面板打开时不需要发送模型消息。
   模型无法解析时调用 `unbind(sessionId)`。service 信任此绑定，不等待 `sessions.model_id` 异步更新。
2. `ChatViewModel.runAgentLoop()`：真实 `currentProvider.streamMessage(...)` 之前，按该次
   `loopEntryId` 找到模型一致的真实 entry，调用 `bind`，然后 `begin(activeSessionId)`；
   scriptedTurn / 测试回放跳过。每次重试和回退重新采集实际 provider、entry 和 clamped thinking。
3. 同一 stream collect 的 finally：先截取 `SystemClock.elapsedRealtime()`，在 `NonCancellable`
   中调用 `finish(request, attemptUsage, completed=collectDone, endedElapsed=end)`。
   `attemptUsage` 必须是 API 此次请求的实测 usage（保持现有 UsageChunkMerger 规则），
   不能传累计 turn usage、文本长度估算或上下文估算。取消/出错也需 finish 释放 active 状态。
4. 两个 `persistAssistantTurn(...)` 分支返回 DB message ID 后，调用
   `attachMessage(activeSessionId, forgeRequestIds, dbMessageId)`，关联该消息涵盖的 API request IDs。
   一条消息只有在关联请求 token 与其保存 token 完全相等时，才使用请求快照拆分模型用量。
   不相等时保留数据库原始用量，避免漏算或重复相加。

这些 hook 已由主代理在本轮接入；统计代理只读取核对了上述路径，没有修改 ChatViewModel、host、provider 或导航。
普通 hook 存储异常应记录并继续聊天；取消异常继续传播，finally 的收尾除外。

## 数据与速度

- 用量读取同一个 Room messages 表和 ChatDao.getSession，解析复用 `StoredUsageJson`，
  与现有 UsageStats 的缓存读写、copied、非法 JSON、reasoning 子集规则一致。
- 本轮源码没有独立 UsageRepository / LLMProviders 类型；使用已有 UsageStats/StoredUsage、
  ChatDao 数据和 `ProviderFactory`，没有新增共享仓库或 provider 改动。
- 查询只投影当前会话的 ID、token JSON、时间和模型/provider 快照；全局入口显式读取全部会话。
  不读取对话正文。旧模型快照缺失的记录归为“未记录模型”，不按 session 当前模型猜测历史归属。
- input 包含 fresh + cacheRead + cacheWrite；output 已含 reasoning，不再额外相加。
  reasoning 未报告为 null，部分报告以 reasoningComplete 区分。cache 未报告时 UI 显示“—”。
- elapsed 只使用 begin/finish 的单调时钟；没有计时的历史为 null，不根据 created_at、updated_at 或消息间隔生成速度。
  加权速度 = 有计时请求的 output 总量 × 1000 / durationMs 总量。每会话保留最近 2000 个请求 receipt，
  UI 标为“实测加权均速”，显示已保留计时数量。数据库 token 总量不受该上限影响。
- 模型/思考/窗口变化来自捕获的请求设置。窗口标为 request-limit，contextUsed 来自 API latestContextTokens；
  不宣称窗口是服务端返回，也不按模型名称判断升降级。

## 指纹与探测

ModelTrace 原库 SHA-256：`eeab6ff54d6ffb2b294ebfd6cce25bce387f7a268b66759144f64c16f6c66b9f`。
Kotlin 端保留稳健 Hellinger/顺序特征、原校准、家族概率、相同判据和 24 小时过期机制。
未知模型始终 UNLISTED，不因最近候选产生替换告警。概率只表示参考库内相对概率。

主动 probe 仅由用户按钮触发，顺序发送三次请求。每份样本都由 `ProviderFactory.create` 创建全新 provider、
全新 oneOffSessionId，使用捕获的实际模型、服务商实例、entry overrides 和有效 thinking。
只发送数字采样用户消息，systemPrompt=null，历史/图片/tools 为空，不进入 agent loop，
工具/媒体输出立即拒绝，没有插件层自动重试。所有请求总时限 240 秒。

图片工具专用路由及非文字模型不允许探测。由于现有 OpenAI provider 最后合并任意 extraBodyParams，
含工具、历史、远程状态或未知能力字段的 entry 会在发请求前被拒绝；只允许普通采样和推理参数，
不修改用户配置。此类模型仍可手动粘贴数字样本本地分析。

结果同时绑定 session/model/provider-instance/thinking/endpoint SHA-256，保留每会话最近 20 条。
运行中更换绑定或端点，结果不保存。cancel 与结果提交串行；取消后不得写入结果。
provider 原始错误信息不向 UI/历史透传，避免暴露其请求内容。

`analyze` 参数为 `{outputs:[{text,expected_count}]}`，1–3 组，每组 80–500 个要求值、文本不超过 50000 字符。
`import` 只接受调用方明确提供的 `{record:{threadId,model,provider,effort,endpoint,createdAt,outputs}}`，
全部身份字段须与当前 snapshot 一致，时间在 24 小时以内，重新计算原始 outputs，
不信任导入的 analysis。不会读取电脑上的 is-gpt-nerfed 文件。

## UI 与许可

React 默认导出保持 `InformationStatsPanel({api,mode,onMode,onClose,threadId})`，包含概览、模型检测、历史。
onMode=null 时隐藏宽度切换，适配 Android 全屏。api.subscribe 可选；普通读取不触发计费请求。

`information-sources.md` 保存原上游提交和本轮移植说明；`information-notices.txt` 和
`information-forge-license.txt` 保存第三方及 Forge 的完整 MIT 许可。
`information-test-vectors.json` 仅是明确标注的固定种子合成回归样本，用于比对原 JavaScript 算法，
不由产品读取为真实模型样本。

## 验证边界

统计代理不运行 Gradle，也不执行真实 provider / 计费请求。已使用缓存 Kotlin 编译器和应用依赖
进行独立编译/JUnit 回归；统一 Gradle、最终 bundle、手机尺寸浏览器和 Android 设备验收由主代理负责。
没有声称真实提供商行为、真实探测成功或设备安装已经通过。
