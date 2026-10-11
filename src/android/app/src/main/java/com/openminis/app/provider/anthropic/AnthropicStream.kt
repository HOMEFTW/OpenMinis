package com.openminis.app.provider.anthropic

import com.openminis.app.data.model.*
import com.openminis.app.provider.parseToolInputObject
import org.json.JSONArray
import org.json.JSONObject

/** Tool completion is staged until message_stop, never merely content_block_stop. */
internal class AnthropicStream(private val model: String) {
    private data class Block(
        val value: JSONObject,
        val input: StringBuilder = StringBuilder(),
        val text: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        var closed: Boolean = false,
    )
    private val blocks = linkedMapOf<Int, Block>()
    private var reason: String? = null
    var completed = false
        private set

    fun accept(event: JSONObject): List<LLMStreamChunk> = buildList {
        if (event.optString("type") == "error") {
            val error = event.optJSONObject("error")
            throw AnthropicProvider.streamError(error?.optString("type").orEmpty(), error?.optString("message") ?: "Stream failed")
        }
        if (completed) malformed("event after message_stop")
        when (event.optString("type")) {
            "message_start" -> add(LLMStreamChunk.Started)
            "content_block_start" -> {
                val index = event.getInt("index")
                if (index < 0 || blocks.containsKey(index) || reason != null) malformed("duplicate or late block")
                val value = JSONObject(event.getJSONObject("content_block").toString())
                when (value.getString("type")) {
                    "tool_use" -> {
                        if (value.getString("id").isBlank() || value.getString("name").isBlank()) malformed("empty tool identity")
                        value.getJSONObject("input")
                        add(LLMStreamChunk.ToolUseStart(value.getString("id"), value.getString("name")))
                    }
                    "text" -> value.getString("text").takeIf { it.isNotEmpty() }?.let { add(LLMStreamChunk.Text(it)) }
                    "thinking" -> value.getString("thinking").takeIf { it.isNotEmpty() }?.let { add(LLMStreamChunk.ThinkingDelta(it)) }
                    "redacted_thinking" -> value.getString("data")
                    else -> malformed("unsupported block type")
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
                val block = blocks[event.getInt("index")] ?: malformed("unknown block")
                if (block.closed) malformed("block already closed")
                if (event.getString("type") == "content_block_stop") {
                    block.closed = true
                } else {
                    val delta = event.getJSONObject("delta")
                    val value = block.value
                    when (delta.getString("type")) {
                        "text_delta" -> {
                            if (value.getString("type") != "text") malformed("text delta on non-text block")
                            val text = delta.getString("text")
                            block.text.append(text)
                            add(LLMStreamChunk.Text(text))
                        }
                        "thinking_delta" -> {
                            if (value.getString("type") != "thinking") malformed("thinking delta on non-thinking block")
                            val thinking = delta.getString("thinking")
                            block.text.append(thinking)
                            add(LLMStreamChunk.ThinkingDelta(thinking))
                        }
                        "signature_delta" -> {
                            if (value.getString("type") != "thinking") malformed("signature on non-thinking block")
                            block.signature.append(delta.getString("signature"))
                        }
                        "input_json_delta" -> {
                            if (value.getString("type") != "tool_use") malformed("input on non-tool block")
                            block.input.append(delta.getString("partial_json"))
                            add(LLMStreamChunk.ToolInputDelta(value.getString("id"), block.input.toString()))
                        }
                        else -> malformed("unsupported delta")
                    }
                }
            }
            "message_delta" -> {
                event.optJSONObject("delta")?.opt("stop_reason")?.let { if (it is String && it.isNotBlank()) reason = it }
            }
            "message_stop" -> {
                if (reason == null || blocks.values.any { !it.closed }) malformed("unsettled message_stop")
                val discard = reason !in setOf("end_turn", "tool_use", "stop_sequence")
                val replay = JSONArray()
                val calls = mutableListOf<LLMStreamChunk.ToolCallComplete>()
                val ids = mutableSetOf<String>()
                blocks.toSortedMap().values.forEach { block ->
                    val value = block.value
                    when (value.getString("type")) {
                        "text" -> value.put("text", block.text.toString())
                        "thinking" -> {
                            value.put("thinking", block.text.toString())
                            if (block.signature.isNotEmpty()) value.put("signature", block.signature.toString())
                        }
                    }
                    if (value.getString("type") == "tool_use") {
                        if (discard) return@forEach
                        val input = if (block.input.isEmpty()) value.getJSONObject("input") else parseToolInputObject(block.input.toString())
                        value.put("input", input)
                        if (!ids.add(value.getString("id"))) malformed("duplicate tool id")
                        calls.add(LLMStreamChunk.ToolCallComplete(value.getString("id"), value.getString("name"), input))
                    }
                    replay.put(value)
                }
                // Validate the complete batch before exposing any executable call.
                addAll(calls)
                val reasoning = blocks.toSortedMap().values.filter { it.value.optString("type") == "thinking" }
                    .joinToString("") { it.value.getString("thinking") }
                add(LLMStreamChunk.ReasoningContent(reasoning, anthropicReplay = AnthropicReplay.capture(model, replay)))
                add(LLMStreamChunk.Finished(reason, discardToolCalls = discard))
                completed = true
            }
        }
    }

    fun endOfInput() {
        if (!completed) throw LLMError.TransientError("Anthropic stream ended before message_stop")
    }
    private fun malformed(detail: String): Nothing = throw LLMError.DecodingError(IllegalArgumentException("Anthropic Messages: $detail"))
}
