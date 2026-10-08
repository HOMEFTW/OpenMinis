package com.openminis.app.provider.deepseek

import com.openminis.app.data.model.*
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SseProgressWatchdog
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.cancelOnCancellation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Direct DeepSeek API-key route. No OpenAI/Claude provider or protocol fallback. */
class DeepSeekProvider(
    private val apiKey: String,
    override var model: LLMModel,
    baseURL: String? = null,
    private val customUserAgent: String? = null,
    private val responseTimeoutSeconds: Int? = null,
) : LLMProvider {
    override val name = "DeepSeek"
    override val defaultMaxOutputTokens = 256_000
    private val endpoint = messagesEndpoint(baseURL)
    private val idleTimeoutMs = responseTimeoutSeconds?.takeIf { it > 0 }?.toLong()?.times(1000) ?: 300_000L
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(idleTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
        .build()

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        val text = StringBuilder()
        var usage: LLMUsage? = null
        var reason: String? = null
        streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel).collect {
            when (it) {
                is LLMStreamChunk.Text -> text.append(it.text)
                is LLMStreamChunk.Usage -> usage = it.usage
                is LLMStreamChunk.Finished -> reason = it.stopReason
                else -> Unit
            }
        }
        return LLMResponse(text.toString(), reason, usage)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        if (apiKey.isBlank()) throw LLMError.InvalidApiKey()
        val requestModel = model
        val payload = DeepSeekMessages.request(requestModel, messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val request = Request.Builder().url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .applyUserAgentOverride(customUserAgent)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        coroutineScope {
            val call = client.newCall(request)
            val cancellation = cancelOnCancellation { call.cancel() }
            val watchdog = SseProgressWatchdog(idleTimeoutMs = idleTimeoutMs, onTimeout = { call.cancel() })
            var watchdogJob: Job? = null
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        val error = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                        throw deepSeekError(response.code, error)
                    }
                    val body = response.body ?: throw LLMError.TransientError("DeepSeek returned no response body")
                    watchdogJob = watchdog.start(this)
                    val parser = DeepSeekStream(requestModel.id)
                    val data = StringBuilder()
                    suspend fun dispatch() {
                        if (data.isEmpty()) return
                        val event = parseDeepSeekObject(data.toString())
                        data.setLength(0)
                        val chunks = try { parser.accept(event) } catch (e: org.json.JSONException) { throw LLMError.DecodingError(e) }
                        chunks.forEach { chunk ->
                            val progress = when (chunk) {
                                is LLMStreamChunk.Text -> chunk.text.isNotEmpty()
                                is LLMStreamChunk.ThinkingDelta -> chunk.text.isNotEmpty()
                                is LLMStreamChunk.ReasoningContent -> chunk.content.isNotEmpty()
                                is LLMStreamChunk.Started, is LLMStreamChunk.Usage -> false
                                else -> true
                            }
                            if (progress) watchdog.markProgress()
                            emit(chunk)
                        }
                    }
                    body.charStream().buffered().use { reader ->
                        while (!parser.completed) {
                            currentCoroutineContext().ensureActive()
                            val line = reader.readLine() ?: break
                            when {
                                line.isEmpty() -> dispatch()
                                line == "data" || line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data").removePrefix(":").removePrefix(" "))
                                }
                            }
                        }
                        if (!parser.completed) dispatch()
                        parser.endOfInput()
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw LLMError.NetworkError(watchdog.timeoutException ?: error)
            } finally {
                watchdogJob?.cancel()
                cancellation.cancel()
                call.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"

        internal fun messagesEndpoint(baseURL: String?): String {
            val base = (baseURL?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL).trimEnd('/')
            val url = base.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "DeepSeek base URL must not contain credentials, query or fragment"
            }
            return if (url.encodedPath.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }
}
