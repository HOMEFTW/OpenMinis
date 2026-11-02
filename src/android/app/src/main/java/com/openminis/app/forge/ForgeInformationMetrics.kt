package com.openminis.app.forge

import com.openminis.app.usage.StoredUsage
import com.openminis.app.usage.StoredUsageJson
import com.openminis.app.usage.StoredUsageParse
import org.json.JSONArray
import org.json.JSONObject

internal data class ForgeInformationContext(
    val sessionId: String,
    val model: String,
    val provider: String,
    val effort: String,
    val endpoint: String = "",
) {
    fun matches(record: JSONObject): Boolean = record.optString("threadId") == sessionId &&
        record.optString("model") == model && record.optString("provider") == provider &&
        record.optString("effort") == effort && record.optString("endpoint") == endpoint

    fun json(): JSONObject = JSONObject().put("threadId", sessionId).put("model", model)
        .put("provider", provider).put("effort", effort).put("endpoint", endpoint)

    companion object {
        fun requireSession(trusted: String?, vararg claimed: Any?): String {
            require(!trusted.isNullOrBlank() && trusted.length <= 200) { "请先打开一个已保存的对话" }
            require(claimed.all { it == null || it === JSONObject.NULL || it == trusted }) { "当前对话已变化，请刷新统计面板" }
            return trusted
        }
    }
}

/** Narrow DB projection: token JSON and immutable attribution, never message text. */
internal data class ForgeInformationUsageRow(
    val id: String,
    val sessionId: String,
    val model: String?,
    val provider: String?,
    val createdAt: Long,
    val usageJson: String?,
)

/** Keeps the existing StoredUsage numeric/cache/copied rules. Reasoning is a subset of output. */
internal object ForgeInformationMetrics {
    fun aggregate(sessionId: String?, rows: List<ForgeInformationUsageRow>, receipts: List<JSONObject>): JSONObject {
        var invalid = 0
        var copied = 0
        var unrecordedModel = 0
        var reasoningRows = 0
        var cacheRows = 0
        var input = 0L
        var cached = 0L
        var written = 0L
        var output = 0L
        var reasoning = 0L
        var responses = 0
        val models = linkedMapOf<Pair<String?, String?>, JSONObject>()
        val validIds = HashSet<String>()
        val scopedReceipts = receipts.filter { sessionId == null || it.optString("threadId") == sessionId }
            .distinctBy { it.optString("id") }.filter { it.optString("id").isNotEmpty() }
        fun add(usage: StoredUsage, model: String?, provider: String?) {
            input += usage.input + usage.cacheRead + usage.cacheWrite
            cached += usage.cacheRead
            written += usage.cacheWrite
            output += usage.output
            usage.reasoning?.let { reasoning += it; reasoningRows++ }
            if (usage.cacheReported == true || usage.cacheRead > 0 || usage.cacheWrite > 0) cacheRows++
            if (model == null) unrecordedModel++
            responses++
            val group = models.getOrPut(model to provider) { JSONObject().nullable("model", model).nullable("provider", provider)
                .put("input", 0L).put("output", 0L).put("cached", 0L).put("responses", 0) }
            group.put("input", group.getLong("input") + usage.input + usage.cacheRead + usage.cacheWrite)
                .put("output", group.getLong("output") + usage.output).put("cached", group.getLong("cached") + usage.cacheRead)
                .put("responses", group.getInt("responses") + 1)
        }
        rows.filter { sessionId == null || it.sessionId == sessionId }.forEach { row ->
            if (!validIds.add(row.id)) return@forEach
            val parsed = StoredUsageJson.parse(row.usageJson)
            if (parsed is StoredUsageParse.Invalid) { invalid++; return@forEach }
            val usage = (parsed as? StoredUsageParse.Valid)?.usage ?: return@forEach
            if (usage.copied) { copied++; return@forEach }
            // Several API calls may form one persisted assistant message. Replace that row only
            // when every measured token is accounted for exactly; otherwise preserve the DB truth.
            val linked = scopedReceipts.filter { it.optString("messageId") == row.id && it.optString("threadId") == row.sessionId }
            val measured = linked.mapNotNull { receipt ->
                (StoredUsageJson.parse(receipt.optJSONObject("usage")?.toString()) as? StoredUsageParse.Valid)?.usage
                    ?.let { receipt to it }
            }
            val complete = measured.isNotEmpty() && measured.size == linked.size &&
                measured.sumOf { it.second.input } == usage.input && measured.sumOf { it.second.output } == usage.output &&
                measured.sumOf { it.second.cacheRead } == usage.cacheRead && measured.sumOf { it.second.cacheWrite } == usage.cacheWrite
            if (complete) measured.forEach { (receipt, u) -> add(u, receipt.optString("model").ifBlank { null }, receipt.optString("provider").ifBlank { null }) }
            else add(usage, row.model, row.provider)
        }
        // Never infer elapsed time from DB creation/update timestamps or gaps between messages.
        val timed = scopedReceipts.mapNotNull { receipt ->
            if (receipt.optString("status", "completed") != "completed") return@mapNotNull null
            val u = (StoredUsageJson.parse(receipt.optJSONObject("usage")?.toString()) as? StoredUsageParse.Valid)?.usage
                ?: return@mapNotNull null
            if (u.copied) return@mapNotNull null
            val duration = (receipt.opt("durationMs") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            val end = (receipt.opt("endMs") as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            JSONObject().put("id", receipt.getString("id")).put("model", receipt.optString("model"))
                .put("provider", receipt.optString("provider")).put("endMs", end).put("durationMs", duration)
                .put("output", u.output).put("input", u.input + u.cacheRead + u.cacheWrite)
                .put("cachedInput", u.cacheRead).nullable("reasoning", u.reasoning).put("tps", u.output * 1000.0 / duration)
        }.sortedBy { it.getLong("endMs") }
        val elapsed = timed.sumOf { it.getDouble("durationMs") }
        return JSONObject().put("inputTokens", input).put("cachedInputTokens", cached).put("cacheWriteTokens", written)
            .put("outputTokens", output).nullable("reasoningTokens", reasoning.takeIf { reasoningRows > 0 })
            .put("reasoningComplete", responses > 0 && reasoningRows == responses).put("cacheReportedResponses", cacheRows)
            .put("totalTokens", input + output).put("responses", responses).put("measuredResponses", timed.size)
            .nullable("elapsedMs", elapsed.takeIf { timed.isNotEmpty() })
            .nullable("average", if (elapsed > 0) timed.sumOf { it.getLong("output") } * 1000.0 / elapsed else null)
            .nullable("latest", timed.lastOrNull()).put("samples", JSONArray(timed.takeLast(30)))
            .put("models", JSONArray(models.values.toList())).put("malformed", invalid).put("copiedSkipped", copied)
            .put("unrecordedModelResponses", unrecordedModel).put("found", responses > 0 || timed.isNotEmpty())
            .put("timing", "end-to-end").put("scope", if (sessionId == null) "global" else "session").put("reading", false)
    }
}

/** Passive observations: compares captured request settings, never guesses server identity. */
internal object ForgeInformationObservation {
    fun observe(state: JSONObject, current: JSONObject, applied: Boolean, at: Long, requestId: String? = null) {
        val previous = state.optJSONObject("current")
        val events = state.optJSONArray("events")?.objects().orEmpty().toMutableList()
        val sameModel = previous?.optString("model") == current.optString("model") &&
            previous.optString("provider") == current.optString("provider")
        fun event(kind: String, before: Any?, after: Any?, warning: Boolean) {
            if (before == null || before === JSONObject.NULL || after == null || after === JSONObject.NULL || before == after) return
            events += JSONObject().put("kind", kind).put("severity", if (warning && !applied) "warning" else "info")
                .put("detail", "$before → $after（${if (applied) "已应用设置" else "未见设置变更记录"}）")
                .put("at", at).nullable("turnId", requestId)
        }
        event("model_change", previous?.opt("model"), current.opt("model"), true)
        event("provider_change", previous?.opt("provider"), current.opt("provider"), true)
        val ranks = listOf("OFF", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX", "ULTRA")
        val beforeEffort = previous?.optString("effort").orEmpty()
        val afterEffort = current.optString("effort")
        if (beforeEffort.isNotBlank() && afterEffort.isNotBlank()) event("effort_change", beforeEffort, afterEffort,
            ranks.indexOf(afterEffort) in 0 until ranks.indexOf(beforeEffort))
        if (sameModel) event("context_change", previous?.opt("contextWindow"), current.opt("contextWindow"),
            current.optLong("contextWindow") < (previous?.optLong("contextWindow") ?: 0))
        state.put("current", current).put("events", JSONArray(events.takeLast(80)))
    }

    fun snapshot(state: JSONObject, fallback: ForgeInformationContext): JSONObject {
        val current = state.optJSONObject("current")?.takeIf { it.optString("model") == fallback.model && it.optString("provider") == fallback.provider }
        val events = state.optJSONArray("events")?.objects().orEmpty().asReversed()
        val window = (current?.opt("contextWindow") as? Number)?.toLong()?.takeIf { it > 0 }
        val used = (current?.opt("contextUsed") as? Number)?.toLong()?.takeIf { it >= 0 }
        return JSONObject().put("model", fallback.model).put("effort", fallback.effort)
            .put("events", JSONArray(events)).put("warnings", events.count { it.optString("severity") == "warning" })
            .nullable("contextWindow", window).nullable("contextUsed", used)
            .nullable("contextPercent", if (window != null && used != null) used.toDouble() / window else null)
            .put("contextWindowSource", current?.optString("contextWindowSource").orEmpty())
            .put("identitySource", "request-settings")
    }
}
