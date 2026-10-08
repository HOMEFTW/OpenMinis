package com.openminis.app.provider.deepseek

import com.openminis.app.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** DeepSeek Harness Messages subset, pinned in docs/superpowers/plans/2026-10-08-deepseek-native.md. */
internal object DeepSeekMessages {
    fun request(
        model: LLMModel, messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
        temperature: Double?, images: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
        thinking: ThinkingLevel,
    ): JSONObject {
        val wire = JSONArray()
        val lastUser = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        require(images.isEmpty() || lastUser >= 0) { "DeepSeek images require a user message" }
        messages.forEachIndexed { index, message ->
            if (message.audioParts.isNotEmpty()) {
                throw LLMError.ProviderError("DeepSeek Messages does not support audio input")
            }
            val content = JSONArray()
            if (message.role == LLMMessage.Role.ASSISTANT) {
                val replay = readReplay(message, model.id)
                if (replay != null) {
                    // Persist exact block order, text and signatures for native multi-tool replies.
                    replay.forEachObject { content.put(it) }
                } else {
                    message.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                        content.put(JSONObject().put("type", "thinking").put("thinking", it))
                    }
                }
                if (replay == null && message.contentParts.isEmpty()) {
                    if (message.content.isNotEmpty()) content.put(text(message.content))
                } else if (replay == null) {
                    message.contentParts.forEach { content.put(assistantPart(it)) }
                }
            } else {
                if (message.contentParts.isEmpty()) {
                    if (message.content.isNotEmpty()) content.put(text(message.content))
                    message.imageParts.forEach { content.put(image(model, it.data, it.mimeType, it.noVisionPlaceholder)) }
                } else {
                    message.contentParts.forEach { part -> content.put(when (part) {
                        is AgentContentPart.Text -> text(part.text)
                        is AgentContentPart.ImageData -> image(model, part.data, part.mimeType, part.noVisionPlaceholder)
                        is AgentContentPart.ToolResult -> JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", part.id)
                            put("is_error", part.isError)
                            put("content", JSONArray().apply {
                                if (part.content.isNotEmpty()) put(text(part.content))
                                part.imageData?.let { put(image(model, it, part.imageMimeType ?: "image/jpeg", null)) }
                            })
                        }
                        is AgentContentPart.ToolUse -> throw LLMError.ProviderError("DeepSeek tool call must belong to an assistant")
                    }) }
                }
                if (index == lastUser) images.forEach { content.put(image(model, it.data, it.mimeType, it.noVisionPlaceholder)) }
            }
            if (content.length() > 0) {
                val role = message.role.value
                val previous = wire.optJSONObject(wire.length() - 1)
                if (previous?.optString("role") == role) content.forEachObject { previous.getJSONArray("content").put(it) }
                else wire.put(JSONObject().put("role", role).put("content", content))
            }
        }
        validateTools(wire)
        return JSONObject().apply {
            put("model", model.id)
            put("stream", true)
            put("max_tokens", maxTokens.coerceIn(1, model.maxOutputTokens ?: 256_000))
            put("messages", wire)
            systemPrompt?.takeIf { it.isNotEmpty() }?.let { put("system", it) }
            put("thinking", JSONObject().put("type", if (thinking == ThinkingLevel.OFF) "disabled" else "enabled"))
            if (thinking != ThinkingLevel.OFF) put("output_config", JSONObject().put("effort", when (thinking) {
                ThinkingLevel.LOW -> "low"
                ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
                else -> "high"
            }))
            temperature?.let { put("temperature", it) }
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(it.toAnthropicJson()) } })
        }
    }

    private fun assistantPart(part: AgentContentPart): JSONObject = when (part) {
        is AgentContentPart.Text -> text(part.text)
        is AgentContentPart.ToolUse -> JSONObject().put("type", "tool_use").put("id", part.id)
            .put("name", part.name).put("input", part.input)
        else -> throw LLMError.ProviderError("DeepSeek assistant history contains unsupported content")
    }

    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun image(model: LLMModel, data: ByteArray, mime: String, placeholder: String?): JSONObject {
        if (model.inputModalities?.any { it == "image" || it == "image_input" } != true) return text(placeholder ?: "[Image omitted: selected model does not support image input]")
        return JSONObject().put("type", "image").put("source", JSONObject()
            .put("type", "base64").put("media_type", mime).put("data", Base64.getEncoder().encodeToString(data)))
    }

    private fun validateTools(messages: JSONArray) {
        val pending = mutableSetOf<String>()
        messages.forEachObject { message ->
            val blocks = message.getJSONArray("content")
            if (message.getString("role") == "assistant") {
                if (pending.isNotEmpty()) invalidTools()
                blocks.forEachObject { if (it.optString("type") == "tool_use") {
                    if (!pending.add(it.getString("id"))) invalidTools()
                } }
            } else {
                val ordered = JSONArray()
                blocks.forEachObject { if (it.optString("type") == "tool_result") {
                    if (!pending.remove(it.getString("tool_use_id"))) invalidTools()
                    ordered.put(it)
                } }
                if (pending.isNotEmpty()) invalidTools()
                blocks.forEachObject { if (it.optString("type") != "tool_result") ordered.put(it) }
                message.put("content", ordered)
            }
        }
        if (pending.isNotEmpty()) invalidTools()
    }

    private fun invalidTools(): Nothing = throw LLMError.ProviderError("DeepSeek tool history has missing, duplicate or unmatched results")

    // Store only offsets, signatures and hashes, not a second copy of long assistant output.
    fun replay(model: String, blocks: JSONArray): String {
        val metadata = JSONArray()
        blocks.forEachObject { block ->
            val type = block.getString("type")
            metadata.put(JSONObject().put("type", type).apply {
                when (type) {
                    "text", "thinking" -> {
                        val value = block.getString(if (type == "text") "text" else "thinking")
                        put("length", value.length)
                        put("hash", hash(value))
                        if (type == "thinking" && block.has("signature")) put("signature", block.getString("signature"))
                    }
                    "tool_use" -> {
                        put("id", block.getString("id"))
                        put("name", block.getString("name"))
                        put("hash", inputHash(block.getJSONObject("input")))
                    }
                }
            })
        }
        return JSONObject().put("version", 1).put("model", model).put("blocks", metadata).toString()
    }

    fun readReplay(message: LLMMessage, model: String): JSONArray? = runCatching {
        val root = JSONObject(message.deepSeekReplay ?: return null)
        if (root.optInt("version") != 1 || root.optString("model") != model) return null
        val visibleText = if (message.contentParts.isEmpty()) message.content
            else message.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }
        val reasoning = message.reasoningContent.orEmpty()
        val tools = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        var textOffset = 0
        var thinkingOffset = 0
        var toolIndex = 0
        val blocks = JSONArray()
        root.getJSONArray("blocks").forEachObject { meta ->
            when (val type = meta.getString("type")) {
                "text", "thinking" -> {
                    val offset = if (type == "text") textOffset else thinkingOffset
                    val value = (if (type == "text") visibleText else reasoning).substring(offset, offset + meta.getInt("length"))
                    if (hash(value) != meta.getString("hash")) return null
                    blocks.put(JSONObject().put("type", type).put(if (type == "text") "text" else "thinking", value).apply {
                        if (type == "thinking" && meta.has("signature")) put("signature", meta.getString("signature"))
                    })
                    if (type == "text") textOffset += value.length else thinkingOffset += value.length
                }
                "tool_use" -> {
                    val tool = tools.getOrNull(toolIndex++) ?: return null
                    if (meta.getString("id") != tool.id || meta.getString("name") != tool.name || meta.getString("hash") != inputHash(tool.input)) return null
                    blocks.put(assistantPart(tool))
                }
                else -> return null
            }
        }
        if (textOffset != visibleText.length || thinkingOffset != reasoning.length || toolIndex != tools.size) return null
        blocks
    }.getOrNull()

    private fun hash(value: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private fun inputHash(input: JSONObject): String = hash(canonical(Json.parseToJsonElement(input.toString())).toString())
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) action(getJSONObject(index))
    }
}
