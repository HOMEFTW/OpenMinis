package com.openminis.app.provider.anthropic

import com.openminis.app.data.model.*
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Preserve exact signed/redacted blocks and their order without duplicating assistant text. */
internal object AnthropicReplay {
    fun capture(model: String, blocks: JSONArray): String {
        val metadata = JSONArray()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            metadata.put(JSONObject().put("type", block.getString("type")).apply {
                when (block.getString("type")) {
                    "text", "thinking" -> {
                        val field = if (block.getString("type") == "text") "text" else "thinking"
                        val value = block.getString(field)
                        put("length", value.length)
                        put("hash", hash(value))
                        if (field == "thinking" && block.has("signature")) put("signature", block.getString("signature"))
                    }
                    "redacted_thinking" -> put("data", block.getString("data"))
                    "tool_use" -> {
                        put("name", block.getString("name"))
                        put("hash", inputHash(block.getJSONObject("input")))
                    }
                }
            })
        }
        return JSONObject().put("version", 1).put("model", model).put("blocks", metadata).toString()
    }

    fun read(message: LLMMessage, model: String): JSONArray? = runCatching {
        val root = JSONObject(message.anthropicReplay ?: return null)
        if (root.optInt("version") != 1 || root.optString("model") != model) return null
        val visible = if (message.contentParts.isEmpty()) message.content else
            message.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }
        val reasoning = message.reasoningContent.orEmpty()
        val tools = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        var textOffset = 0
        var thinkingOffset = 0
        var toolIndex = 0
        val replay = JSONArray()
        val metadata = root.getJSONArray("blocks")
        for (i in 0 until metadata.length()) {
            val meta = metadata.getJSONObject(i)
            val type = meta.getString("type")
            val block = JSONObject().put("type", type)
            when (type) {
                "text", "thinking" -> {
                    val offset = if (type == "text") textOffset else thinkingOffset
                    val value = (if (type == "text") visible else reasoning).substring(offset, offset + meta.getInt("length"))
                    if (hash(value) != meta.getString("hash")) return null
                    block.put(if (type == "text") "text" else "thinking", value)
                    if (type == "thinking") {
                        val signature = meta.opt("signature") as? String
                        if (signature.isNullOrBlank()) return null
                        block.put("signature", signature)
                        thinkingOffset += value.length
                    } else textOffset += value.length
                }
                "redacted_thinking" -> block.put("data", meta.getString("data"))
                "tool_use" -> {
                    val tool = tools.getOrNull(toolIndex++) ?: return null
                    if (meta.getString("name") != tool.name || meta.getString("hash") != inputHash(tool.input)) return null
                    block.put("id", tool.id).put("name", tool.name).put("input", tool.input)
                }
                else -> return null
            }
            replay.put(block)
        }
        if (textOffset != visible.length || thinkingOffset != reasoning.length || toolIndex != tools.size) return null
        replay
    }.getOrNull()

    private fun hash(value: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun inputHash(input: JSONObject): String = hash(canonical(Json.parseToJsonElement(input.toString())).toString())
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
