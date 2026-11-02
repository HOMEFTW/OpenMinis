package com.openminis.app.forge

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/** Android-only implementation of Forge API 2 statistics. This class never connects to a desktop host. */
class ForgeInformationService(private val context: Context, private val app: MinisApp) {
    private val collector = ForgeInformationCollector.get(context)
    private val runtime by lazy { runtime(context.applicationContext) }

    suspend fun request(method: String, args: JSONObject, sessionId: String?): Any? = withContext(Dispatchers.IO) {
        val operation = if (method == "statistics") args.optString("method", "snapshot") else method
        require(operation in setOf("snapshot", "analyze", "probe", "cancel", "import")) { "不支持的统计操作" }
        val input = if (method == "statistics") args.optJSONObject("args") ?: JSONObject() else args
        if (sessionId == null && operation == "snapshot") {
            require(args.opt("threadId") == null || args.isNull("threadId")) { "当前对话已变化，请刷新统计面板" }
            require(input.opt("threadId") == null || input.isNull("threadId")) { "当前对话已变化，请刷新统计面板" }
            return@withContext globalSnapshot()
        }
        val trusted = ForgeInformationContext.requireSession(sessionId, args.opt("threadId"), input.opt("threadId"))
        val session = app.database.chatDao().getSession(trusted) ?: error("当前对话不存在或已删除")
        require(app.providerRepositoryOrNull != null) { "模型配置尚未就绪" }
        // Host synchronizes the VM selection immediately before this call. The session row
        // can still contain the preceding model while its async persistence is in flight.
        val target = collector.target(trusted)
        val instance = target?.let { binding -> app.providerRepository.config.value.instances.firstOrNull { it.id == binding.providerInstanceId } }
        val pinned = ForgeInformationContext(trusted, target?.model?.id ?: session.modelId,
            target?.providerInstanceId.orEmpty(), target?.thinkingLevel?.name ?: session.thinkingOverride.orEmpty(),
            instance?.let(::endpointIdentity).orEmpty())
        when (operation) {
            "analyze" -> {
                require(pinned.model.isNotBlank()) { "尚未确认当前模型" }
                val outputs = input.optJSONArray("outputs") ?: error("请提供 1 至 3 组数字回答")
                collector.store.save(pinned, runtime.fingerprint.analyze(outputs), "manual", System.currentTimeMillis())
            }
            "import" -> {
                val record = input.optJSONObject("record") ?: error("Android 导入需粘贴含 outputs、会话及模型信息的完整检测 JSON；也可直接粘贴数字样本")
                require(pinned.matches(record)) { "检测记录与当前会话、服务商、模型或思考档位不一致" }
                val createdAt = (record.opt("createdAt") as? Number)?.toLong() ?: error("检测时间缺失")
                require(!ForgeInformationFingerprint.stale(createdAt, System.currentTimeMillis())) { "检测记录已过期或时间无效" }
                val outputs = record.optJSONArray("outputs") ?: error("导入记录没有原始数字样本")
                collector.store.save(pinned, runtime.fingerprint.analyze(outputs), "is-gpt-nerfed", createdAt)
            }
            "cancel" -> runtime.jobs.cancel(trusted)
            "probe" -> {
                require(target != null && instance != null && instance.isEnabled && instance.providerType.isUsable) {
                    "尚未接入当前真实请求绑定；请先在当前会话发送消息，或粘贴数字样本分析"
                }
                probePolicyReason(target)?.let { error(it) }
                // Only this explicit operation may create a provider or make a network request.
                val isolatedInstance = instance.copy()
                val stillCurrent = {
                    collector.target(trusted)?.revision == target.revision &&
                        app.providerRepository.config.value.instances.firstOrNull { it.id == target.providerInstanceId }
                            ?.let { it.isEnabled && endpointIdentity(it) == pinned.endpoint } == true
                }
                runtime.jobs.start(pinned, stillCurrent, run = { progress ->
                    runProbe(target, isolatedInstance, stillCurrent, progress)
                }, save = { outputs ->
                    collector.store.save(pinned, runtime.fingerprint.analyze(outputs), "probe", System.currentTimeMillis())
                })
            }
        }
        snapshot(pinned, target, instance)
    }

    private fun snapshot(
        current: ForgeInformationContext,
        target: ForgeInformationCollector.Target?,
        instance: ProviderInstance?,
    ): JSONObject {
        val rows = usageRows(current.sessionId)
        val state = collector.store.read(current.sessionId)
        val receipts = state.optJSONArray("receipts")?.objects().orEmpty()
        val speed = ForgeInformationMetrics.aggregate(current.sessionId, rows, receipts)
            .put("phase", if (collector.generating(current.sessionId)) "generating" else "idle")
            .put("observation", ForgeInformationObservation.snapshot(state.optJSONObject("observation") ?: JSONObject(), current))
            .put("timingHookConnected", target != null)
        val records = state.optJSONArray("history")?.objects().orEmpty().filter { it.optString("threadId") == current.sessionId }.take(20)
        val record = records.firstOrNull(current::matches)
        val analysis = record?.optJSONObject("analysis")
        val createdAt = (record?.opt("createdAt") as? Number)?.toLong()
        val stale = createdAt != null && ForgeInformationFingerprint.stale(createdAt, System.currentTimeMillis())
        val fingerprint = runtime.fingerprint.assess(current.model, analysis).apply {
            put("stale", stale)
            put("warning", !stale && optBoolean("warning"))
            put("expected", current.model).nullable("prediction", analysis?.opt("prediction"))
                .nullable("probability", analysis?.opt("probability")).put("usedOutputs", analysis?.optInt("used_outputs") ?: 0)
                .put("results", analysis?.optJSONArray("results") ?: JSONArray())
                .put("diagnostics", analysis?.optJSONArray("diagnostics") ?: JSONArray())
                .put("families", analysis?.optJSONArray("family_probabilities") ?: JSONArray())
                .nullable("calibration", analysis?.optJSONObject("calibration")).nullable("createdAt", createdAt)
                .put("source", record?.optString("source").orEmpty())
        }
        val history = records.map { item ->
            val expired = ForgeInformationFingerprint.stale(item.optLong("createdAt"), System.currentTimeMillis())
            JSONObject().put("id", item.optString("id")).put("model", item.optString("model"))
                .put("provider", item.optString("provider")).put("effort", item.optString("effort"))
                .put("createdAt", item.optLong("createdAt")).put("source", item.optString("source"))
                .nullable("prediction", item.optJSONObject("analysis")?.opt("prediction"))
                .put("verdict", runtime.fingerprint.assess(item.optString("model"), item.optJSONObject("analysis")).getString("verdict"))
                .put("stale", expired).put("current", current.matches(item))
        }
        val unavailable = if (target == null) "等待当前实际模型绑定" else if (instance == null || !instance.isEnabled || !instance.providerType.isUsable)
            "提供商实例不可用" else probePolicyReason(target)
        return current.json().put("speed", speed).put("fingerprint", fingerprint).put("history", JSONArray(history))
            .nullable("job", runtime.jobs.snapshot(current)).put("bank", bankSummary())
            .put("providerLabel", instance?.label ?: current.provider)
            .put("probeAvailable", unavailable == null).put("probeUnavailableReason", unavailable.orEmpty())
            .put("sources", "forge/information-sources.md").put("notices", "forge/information-notices.txt")
    }

    /** Same persisted fields and StoredUsage rules used by UsageStats; missing snapshots stay unknown.
     * A narrow projection avoids loading multi-MB message bodies or another session's rows.
     */
    private fun usageRows(sessionId: String?): List<ForgeInformationUsageRow> {
        val result = mutableListOf<ForgeInformationUsageRow>()
        app.database.openHelper.readableDatabase.query(
            "SELECT id, model_id, provider_instance_id, provider_type, created_at, token_usage, session_id FROM messages " +
                "WHERE ${if (sessionId != null) "session_id = ? AND " else ""}role = 'assistant' AND token_usage IS NOT NULL ORDER BY created_at, sort_order, id",
            if (sessionId != null) arrayOf<Any?>(sessionId) else emptyArray<Any?>(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += ForgeInformationUsageRow(
                cursor.getString(0), cursor.getString(6), cursor.getString(1), cursor.getString(2) ?: cursor.getString(3)?.let { "type:$it" },
                cursor.getLong(4), cursor.getString(5),
            )
        }
        return result
    }

    private suspend fun runProbe(
        target: ForgeInformationCollector.Target,
        instance: ProviderInstance,
        stillCurrent: () -> Boolean,
        progress: (Int) -> Unit,
    ): JSONArray = withTimeout(240_000) {
        val outputs = JSONArray()
        repeat(3) { index ->
            coroutineContext.ensureActive()
            check(stillCurrent()) { "当前模型设置已变化" }
            val key = app.providerRepository.usableApiKey(instance)
            require(key != null || instance.credentialType == ProviderCredential.oauth) { "提供商凭据不可用" }
            // Fresh provider + fresh session ID for every sample. No conversation, tool, project,
            // agent, desktop, shell, system prompt, previous response ID or automatic retry is inherited.
            val provider = ProviderFactory.create(instance, key.orEmpty(), target.model, context.applicationContext,
                ProviderFactory.oneOffSessionId(), target.overrides)
            check(provider.model.id == target.model.id && provider.clampThinkingLevel(target.thinkingLevel) == target.thinkingLevel)
            val count = 292 + SecureRandom().nextInt(41)
            val text = StringBuilder()
            var finished = false
            provider.streamMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, prompt(count, index % 2 == 1))),
                systemPrompt = null, maxTokens = minOf(target.model.maxOutputTokens?.takeIf { it > 0 } ?: 8192, 8192),
                tools = emptyList(), imageParts = emptyList(), thinkingLevel = target.thinkingLevel,
            ).collect { chunk ->
                coroutineContext.ensureActive()
                check(stillCurrent()) { "当前模型设置已变化" }
                when (chunk) {
                    is LLMStreamChunk.Text -> { text.append(chunk.text); require(text.length <= 50_000) { "检测回答过长" } }
                    is LLMStreamChunk.ToolUseStart, is LLMStreamChunk.ToolInputDelta, is LLMStreamChunk.ToolCallComplete,
                    is LLMStreamChunk.MediaAttachment -> error("检测尝试调用工具或返回媒体，未接收此样本")
                    is LLMStreamChunk.Finished -> {
                        require(chunk.stopReason !in setOf("length", "max_tokens", "tool_calls", "tool_use") && chunk.refusalCategory == null) { "检测回答未正常完成" }
                        finished = true
                    }
                    else -> Unit
                }
            }
            coroutineContext.ensureActive()
            require(finished && text.isNotBlank()) { "检测未返回完整数字回答" }
            outputs.put(JSONObject().put("text", text.toString()).put("expected_count", count))
            progress(index + 1)
        }
        outputs
    }

    private fun bankSummary() = JSONObject().put("builtAt", runtime.fingerprint.builtAt).put("models", JSONArray(runtime.fingerprint.modelIds)).put("ttlHours", 24)
    private fun probePolicyReason(target: ForgeInformationCollector.Target): String? = ForgeInformationProbePolicy.unavailableReason(
        target.model.isTextOutput, target.model.id == "gpt-image-2" || target.model.isGPTImage25,
        target.overrides?.extraBodyParams?.let { JSONObject(it.toString()) },
    )
    private fun globalSnapshot(): JSONObject {
        val rows = usageRows(null)
        val receipts = rows.map { it.sessionId }.distinct().flatMap { collector.store.read(it).optJSONArray("receipts")?.objects().orEmpty() }
        val speed = ForgeInformationMetrics.aggregate(null, rows, receipts).put("phase", "idle")
            .put("observation", JSONObject()).put("sessionCount", rows.map { it.sessionId }.distinct().size)
        return JSONObject().nullable("threadId", null).put("model", "").put("provider", "").put("effort", "")
            .put("speed", speed).put("fingerprint", runtime.fingerprint.assess("", null)).put("history", JSONArray())
            .nullable("job", null).put("bank", bankSummary()).put("probeAvailable", false).put("scope", "global")
    }

    private class Runtime(context: Context) {
        val fingerprint = ForgeInformationFingerprint(JSONObject(context.assets.open("forge/information-bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }))
        val jobs = ForgeInformationProbeJobs(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
    companion object {
        @Volatile private var shared: Runtime? = null
        private fun runtime(context: Context): Runtime = shared ?: synchronized(this) { shared ?: Runtime(context).also { shared = it } }
        private fun endpointIdentity(instance: ProviderInstance) = informationHash("${instance.providerType.name}|${instance.effectiveBaseURL.orEmpty()}|${instance.useResponsesAPI}")
        private fun prompt(count: Int, chinese: Boolean): String = if (chinese)
            "直接选择 $count 个 1 到 355（含边界）的整数。允许重复；不要排序、平衡频数、修复重复，也不要刻意构造等差规律。\n直接回答一个 JSON 整数数组，不要解释。不要调用工具、读文件、运行代码或让其他模型代答；不要继续之前的任务。"
        else "Directly choose $count integers from 1 through 355, inclusive. Allow repeats. Do not sort, balance frequencies, repair duplicates, or deliberately create an arithmetic pattern.\nReply directly with one JSON integer array and no explanation. Do not call tools, read files, execute code or ask another model. Do not continue the preceding task."
    }
}
