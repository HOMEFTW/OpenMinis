package com.openminis.app.forge

import android.content.Context
import android.os.SystemClock
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** No conversation content, prompts, endpoints or credentials are persisted here. */
internal class ForgeInformationStore(private val root: File) {
    private fun file(sessionId: String) = File(root, "${informationHash(sessionId)}.json")

    @Synchronized fun read(sessionId: String): JSONObject {
        val file = file(sessionId)
        if (!file.exists()) return JSONObject()
        require(file.isFile && file.length() <= 4 * 1024 * 1024) { "统计记录文件无效或过大" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    @Synchronized fun update(sessionId: String, change: (JSONObject) -> Unit) {
        val state = read(sessionId)
        change(state)
        require(root.isDirectory || root.mkdirs()) { "无法创建统计记录目录" }
        val temporary = File(root, "${informationHash(sessionId)}.${UUID.randomUUID()}.tmp")
        try {
            val bytes = state.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 4 * 1024 * 1024) { "统计记录超过大小限制" }
            java.io.FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            try {
                java.nio.file.Files.move(temporary.toPath(), file(sessionId).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), file(sessionId).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    fun save(context: ForgeInformationContext, analysis: JSONObject, source: String, at: Long) = update(context.sessionId) { state ->
        val record = context.json().put("id", UUID.randomUUID().toString()).put("analysis", analysis)
            .put("source", source).put("createdAt", at)
        val history = state.optJSONArray("history")?.objects().orEmpty()
        state.put("history", JSONArray((listOf(record) + history).take(20)))
    }
}

internal fun informationHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Independent hooks for ChatViewModel. Call on IO via the suspend methods; no provider patch needed.
 * Bind after selecting/restoring a model and before every actual provider dispatch (including fallback).
 * Finish with the API's per-request usage, then attach persisted assistant row IDs to those requests.
 * See assets/forge/information-integration.md for exact existing call sites and ownership rules.
 */
class ForgeInformationCollector private constructor(context: Context) {
    internal val store = ForgeInformationStore(File(context.applicationContext.filesDir, "forge/information"))
    internal data class Target(
        val sessionId: String,
        val providerInstanceId: String,
        val model: LLMModel,
        val thinkingLevel: ThinkingLevel,
        val overrides: ModelOverrides?,
        val contextWindow: Int?,
        val revision: Long,
    )
    class Request internal constructor(
        val id: String,
        internal val target: Target,
        internal val startedElapsed: Long,
    )
    private val bindings = ConcurrentHashMap<String, Target>()
    private val active = ConcurrentHashMap<String, Request>()
    private val bindingLock = Any()
    private var revision = 0L

    /** The provider has already resolved the actual model, instance, entry overrides and effort ceiling. */
    suspend fun bind(
        sessionId: String,
        providerInstanceId: String,
        provider: LLMProvider,
        thinkingLevel: ThinkingLevel,
        contextWindowTokens: Int? = null,
        overrides: ModelOverrides? = null,
        settingsApplied: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        ForgeInformationContext.requireSession(sessionId)
        require(providerInstanceId.isNotBlank()) { "缺少真实服务商实例" }
        val effective = provider.clampThinkingLevel(thinkingLevel)
        var changed = false
        val target = synchronized(bindingLock) {
            val old = bindings[sessionId]
            val proposed = Target(sessionId, providerInstanceId, provider.model, effective, overrides,
                contextWindowTokens?.takeIf { it > 0 }, old?.revision ?: 0)
            if (old == proposed) proposed else proposed.copy(revision = ++revision).also { bindings[sessionId] = it; changed = true }
        }
        if (!changed) return@withContext
        store.update(sessionId) { state ->
            val observation = state.optJSONObject("observation") ?: JSONObject()
            val previous = observation.optJSONObject("current")
            val current = target.json().nullable("contextWindow", target.contextWindow)
                .put("contextWindowSource", "request-limit")
            if (previous?.optString("model") == target.model.id && previous.optString("provider") == target.providerInstanceId &&
                previous.optInt("contextWindow") == target.contextWindow) current.nullable("contextUsed", previous.opt("contextUsed"))
            ForgeInformationObservation.observe(observation, current, settingsApplied, System.currentTimeMillis())
            state.put("observation", observation)
        }
    }

    /** Start immediately before streamMessage/sendMessage, after local trimming and tool execution. */
    fun begin(sessionId: String): Request {
        val target = bindings[sessionId] ?: error("请先绑定实际请求设置")
        return Request(UUID.randomUUID().toString(), target, SystemClock.elapsedRealtime()).also { active[it.id] = it }
    }

    /** Capture the end before persistence/other IO. Repeated finish calls cannot create duplicate samples. */
    suspend fun finish(
        request: Request,
        usage: LLMUsage?,
        messageId: String? = null,
        completed: Boolean = true,
        endedElapsed: Long = SystemClock.elapsedRealtime(),
        endedAt: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        if (active.remove(request.id, request).not()) return@withContext
        val duration = (endedElapsed - request.startedElapsed).takeIf { it > 0 }
        store.update(request.target.sessionId) { state ->
            val record = request.target.json().put("id", request.id).put("endMs", endedAt)
                .nullable("durationMs", duration).nullable("messageId", messageId)
                .put("status", if (completed) "completed" else "interrupted")
                .nullable("usage", usage?.informationJson())
            val receipts = state.optJSONArray("receipts")?.objects().orEmpty()
            state.put("receipts", JSONArray((receipts + record).takeLast(2000)))
            val observation = state.optJSONObject("observation")
            val current = observation?.optJSONObject("current")
            if (current?.optString("model") == request.target.model.id &&
                current.optString("provider") == request.target.providerInstanceId &&
                current.optString("effort") == request.target.thinkingLevel.name &&
                current.optInt("contextWindow") == request.target.contextWindow && usage != null && usage.latestContextTokens > 0) {
                current.put("contextUsed", usage.latestContextTokens).put("contextUsedSource", "provider-usage")
            }
        }
    }

    /** Link all API attempts represented by ONE persisted delta row. Never pass cumulative turn usage as a request. */
    suspend fun attachMessage(sessionId: String, requestIds: List<String>, messageId: String): Unit = withContext(Dispatchers.IO) {
        require(messageId.isNotBlank())
        store.update(sessionId) { state ->
            state.optJSONArray("receipts")?.objects()?.filter { it.optString("id") in requestIds && it.optString("threadId") == sessionId }
                ?.forEach { it.put("messageId", messageId) }
        }
    }

    /** Invalidate when selection/binding is unavailable; changing the visible chat alone needn't discard another chat's data. */
    fun unbind(sessionId: String) { bindings.remove(sessionId) }
    internal fun target(sessionId: String): Target? = bindings[sessionId]
    internal fun generating(sessionId: String) = active.values.any { it.target.sessionId == sessionId }

    private fun Target.json() = JSONObject().put("threadId", sessionId).put("model", model.id)
        .put("provider", providerInstanceId).put("effort", thinkingLevel.name)

    companion object {
        @Volatile private var instance: ForgeInformationCollector? = null
        fun get(context: Context): ForgeInformationCollector = instance ?: synchronized(this) {
            instance ?: ForgeInformationCollector(context).also { instance = it }
        }
    }
}

internal fun LLMUsage.informationJson() = JSONObject().put("inputTokens", inputTokens).put("outputTokens", outputTokens)
    .put("cacheReadTokens", cacheReadInputTokens ?: 0).put("cacheCreationTokens", cacheCreationInputTokens ?: 0)
    .put("latestContextTokens", latestContextTokens).apply {
        reasoningTokens?.let { put("reasoningTokens", it) }; cacheReported?.let { put("cacheReported", it) }
    }
