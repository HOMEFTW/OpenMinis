package com.openminis.app.provider.deepseek

import com.openminis.app.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/** One request-local SSE state machine. Never executes a tool from an incomplete response. */
internal class DeepSeekStream(private val model: String) {
    private data class Block(
        val value: JSONObject,
        val json: StringBuilder = StringBuilder(),
        val text: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        var closed: Boolean = false,
    )
    private val blocks = linkedMapOf<Int, Block>()
    private val usage = JSONObject()
    private var started = false
    private var reason: String? = null
    var completed = false
        private set

    fun accept(event: JSONObject): List<LLMStreamChunk> = buildList {
        val type = event.getString("type")
        if (type == "error") throw deepSeekError(null, event)
        if (completed) malformed("event after message_stop")
        if (type == "message_start") {
            if (started) malformed("duplicate message_start")
            started = true
            updateUsage(event.getJSONObject("message").optJSONObject("usage"))
            add(LLMStreamChunk.Started)
            return@buildList
        }
        if (type !in setOf("content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop")) return@buildList
        if (!started) malformed("event before message_start")
        when (type) {
            "content_block_start" -> {
                val index = index(event)
                if (blocks.containsKey(index) || reason != null) malformed("duplicate or late block")
                val value = JSONObject(event.getJSONObject("content_block").toString())
                when (value.getString("type")) {
                    "text" -> value.getString("text").takeIf { it.isNotEmpty() }?.let { add(LLMStreamChunk.Text(it)) }
                    "thinking" -> value.getString("thinking").takeIf { it.isNotEmpty() }?.let { add(LLMStreamChunk.ThinkingDelta(it)) }
                    "tool_use" -> {
                        if (value.getString("id").isEmpty() || value.getString("name").isEmpty()) malformed("empty tool identity")
                        value.getJSONObject("input")
                        add(LLMStreamChunk.ToolUseStart(value.getString("id"), value.getString("name")))
                    }
                    else -> malformed("unsupported content block")
                }
                blocks[index] = Block(value).apply {
                    if (value.getString("type") == "text") text.append(value.getString("text"))
                    if (value.getString("type") == "thinking") {
                        text.append(value.getString("thinking"))
                        signature.append(value.optString("signature", ""))
                    }
                }
            }
            "content_block_delta", "content_block_stop" -> {
                val block = blocks[index(event)] ?: malformed("unknown block")
                if (block.closed) malformed("block already closed")
                val value = block.value
                if (type == "content_block_stop") {
                    block.closed = true
                } else {
                    val delta = event.getJSONObject("delta")
                    when (delta.getString("type")) {
                        "text_delta" -> {
                            if (value.getString("type") != "text") malformed("text delta on non-text block")
                            val text = delta.getString("text")
                            block.text.append(text)
                            add(LLMStreamChunk.Text(text))
                        }
                        "thinking_delta" -> {
                            if (value.getString("type") != "thinking") malformed("thinking delta on non-thinking block")
                            val text = delta.getString("thinking")
                            block.text.append(text)
                            add(LLMStreamChunk.ThinkingDelta(text))
                        }
                        "signature_delta" -> {
                            if (value.getString("type") != "thinking") malformed("signature on non-thinking block")
                            block.signature.append(delta.getString("signature"))
                        }
                        "input_json_delta" -> {
                            if (value.getString("type") != "tool_use") malformed("input on non-tool block")
                            block.json.append(delta.getString("partial_json"))
                            add(LLMStreamChunk.ToolInputDelta(value.getString("id"), block.json.toString()))
                        }
                        else -> malformed("unsupported delta")
                    }
                }
            }
            "message_delta" -> {
                val delta = event.getJSONObject("delta")
                if (!delta.isNull("stop_reason")) {
                    reason = delta.getString("stop_reason")
                    if (reason !in setOf("end_turn", "stop_sequence", "tool_use", "max_tokens")) malformed("unsupported stop reason")
                }
                updateUsage(event.optJSONObject("usage"))
            }
            "message_stop" -> {
                if (reason == null || blocks.values.any { !it.closed }) malformed("unsettled message_stop")
                if (blocks.isEmpty() && reason == "end_turn") throw LLMError.TransientError("DeepSeek returned an empty response")
                val replay = JSONArray()
                val reasoning = StringBuilder()
                val ids = mutableSetOf<String>()
                blocks.values.forEach { block ->
                    val value = block.value
                    when (value.getString("type")) {
                        "text" -> value.put("text", block.text.toString())
                        "thinking" -> {
                            value.put("thinking", block.text.toString())
                            if (block.signature.isNotEmpty()) value.put("signature", block.signature.toString())
                        }
                    }
                    if (value.getString("type") == "tool_use") {
                        // Harness drops ALL calls on an output-limit finish, including valid JSON.
                        if (reason == "max_tokens") return@forEach
                        val input = if (block.json.isEmpty()) value.getJSONObject("input")
                            else parseDeepSeekObject(block.json.toString())
                        value.put("input", input)
                        if (!ids.add(value.getString("id"))) malformed("duplicate tool id")
                        add(LLMStreamChunk.ToolCallComplete(value.getString("id"), value.getString("name"), input))
                    }
                    if (value.getString("type") == "thinking") reasoning.append(value.getString("thinking"))
                    replay.put(value)
                }
                add(LLMStreamChunk.ReasoningContent(reasoning.toString(), DeepSeekMessages.replay(model, replay)))
                val input = usage.optInt("input_tokens")
                val read = usage.optInt("cache_read_input_tokens")
                val write = usage.optInt("cache_creation_input_tokens")
                add(LLMStreamChunk.Usage(LLMUsage(input, usage.optInt("output_tokens"), write, read,
                    (input.toLong() + read + write).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())))
                add(LLMStreamChunk.Finished(reason, discardToolCalls = reason == "max_tokens"))
                completed = true
            }
        }
    }

    fun endOfInput() {
        if (!completed) throw LLMError.TransientError("DeepSeek stream ended before message_stop")
    }

    private fun updateUsage(value: JSONObject?) {
        value ?: return
        listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").forEach { key ->
            if (value.has(key)) {
                val count = value.get(key)
                if (count !is Number || count.toDouble() != count.toLong().toDouble() || count.toLong() !in 0..Int.MAX_VALUE.toLong()) malformed("invalid token usage")
                usage.put(key, count.toInt())
            }
        }
    }

    private fun index(event: JSONObject): Int {
        val value = event.get("index")
        if (value !is Number || value.toDouble() != value.toInt().toDouble() || value.toInt() < 0) malformed("invalid block index")
        return value.toInt()
    }

    private fun malformed(detail: String): Nothing = throw LLMError.DecodingError(IllegalArgumentException("DeepSeek Messages: $detail"))
}

/** JSONObject(String) accepts trailing garbage; executable inputs require a strict full parse. */
internal fun parseDeepSeekObject(raw: String): JSONObject = try {
    val value = kotlinx.serialization.json.Json.parseToJsonElement(raw)
    require(value is kotlinx.serialization.json.JsonObject) { "Expected a JSON object" }
    JSONObject(value.toString())
} catch (error: Exception) {
    throw LLMError.DecodingError(error)
}

internal fun deepSeekError(status: Int?, body: JSONObject): LLMError {
    val error = body.optJSONObject("error")
    val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "DeepSeek request failed (${status ?: "stream error"})"
    val type = error?.optString("type")
    return when {
        status == 401 || status == 403 || type in setOf("authentication_error", "permission_error") -> LLMError.InvalidApiKey(message)
        status == 429 || type == "rate_limit_error" -> LLMError.RateLimited()
        status == 402 || type == "invalid_request_error" -> LLMError.ProviderError(message, status)
        status != null && status >= 500 || type in setOf("api_error", "overloaded_error") -> LLMError.TransientError(message, status)
        else -> LLMError.ProviderError(message, status)
    }
}
