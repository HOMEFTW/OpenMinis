package com.openminis.app.data.repository

import com.openminis.app.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.json.JSONArray
import org.json.JSONObject

internal const val MESSAGE_PARTS_CHAR_BUDGET = 500_000
private const val REASONING_CHAR_BUDGET = 64_000
private val identityFields = setOf("type", "toolUseId", "name", "thoughtSignature", "signature",
    "relativePath", "mimeType", "pageURL", "imageFilePath", "_minisHistoryNotice")
private const val TRUNCATED_INPUT_NOTICE = "Persisted history was truncated; these are not the original arguments and must not be executed."

private fun cappedText(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val marker = "\n[Content truncated; original length ${text.length} chars]"
    val end = (limit - marker.length).coerceAtLeast(0)
    // Avoid cutting a UTF-16 surrogate pair in half.
    val safeEnd = if (end > 0 && text[end - 1].isHighSurrogate()) end - 1 else end
    return text.take(safeEnd) + marker
}

private fun JSONArray.hasSignedReplay(): Boolean = (0 until length()).any { index ->
    val part = optJSONObject(index) ?: return@any false
    part.optString("type") in setOf("anthropicReplay", "deepSeekReplay") ||
        (part.optString("type") == "toolUse" &&
            (part.optJSONObject("value")?.opt("thoughtSignature") as? String)
                ?.let { it.isNotBlank() && it != "null" } == true)
}

/** Keep identities; signed payloads must remain exact and use chunked reads when large. */
internal fun boundedMessagePartsJson(raw: String): String {
    val readable = readablePartsJson(raw)
    if (readable.length <= MESSAGE_PARTS_CHAR_BUDGET) return readable
    require(Json.parseToJsonElement(readable) is JsonArray) { "Message parts must be a JSON array" }
    val parts = JSONArray(readable)
    // Replay hashes/signatures bind the visible text and tool arguments as well
    // as thinking. Preserving just the signature while clipping these fields
    // silently invalidates the next request. The DAO can read large rows in chunks.
    if (parts.hasSignedReplay()) return readable

    fun shrink(value: Any?, limit: Int) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                val child = value.opt(key)
                val signedPayload = (key == "value" && value.optString("type") in setOf("anthropicReplay", "deepSeekReplay")) ||
                    (key in setOf("thinking", "data") && value.optString("type") in setOf("thinking", "redacted_thinking"))
                if (child is String && key !in identityFields && !signedPayload) {
                    if (key == "input" && child.length > limit) {
                        // Tool input is itself JSON; keep it parseable after trimming long fields.
                        val input = runCatching { JSONObject(child) }.getOrNull()
                        if (input != null) {
                            if (!input.optBoolean("_minisHistoryTruncated")) {
                                input.put("_minisHistoryTruncated", true)
                                input.put("_minisOriginalInputChars", child.length)
                            }
                            input.put("_minisHistoryNotice", TRUNCATED_INPUT_NOTICE)
                            shrink(input, limit)
                            value.put(key, input.toString())
                        } else {
                            value.put(key, JSONObject().put("_minisHistoryTruncated", true)
                                .put("_minisOriginalInputChars", child.length)
                                .put("_minisHistoryNotice", TRUNCATED_INPUT_NOTICE)
                                .put("_truncated", cappedText(child, limit)).toString())
                        }
                    } else {
                        value.put(key, cappedText(child, limit))
                    }
                } else if (child !is String) shrink(child, limit)
            }
            is JSONArray -> (0 until value.length()).forEach { index ->
                val child = value.opt(index)
                if (child is String) value.put(index, cappedText(child, limit)) else shrink(child, limit)
            }
        }
    }

    var limit = MESSAGE_PARTS_CHAR_BUDGET / 2
    while (limit >= 64) {
        shrink(parts, limit)
        val result = parts.toString()
        if (result.length <= MESSAGE_PARTS_CHAR_BUDGET) return result
        limit /= 2
    }
    // An enormous identity/signature or number of parts cannot be safely shortened.
    // Fail explicitly instead of silently deleting tool IDs or whole messages.
    throw IllegalArgumentException("Message structure exceeds the storage budget; identities were not discarded")
}

internal fun MessageEntity.forBoundedStorage(): MessageEntity {
    val parts = boundedMessagePartsJson(partsJson)
    val carriesReplay = runCatching { JSONArray(parts).hasSignedReplay() }.getOrDefault(false)
    return copy(
        partsJson = parts,
        // Signed replay verification also binds the original reasoning text.
        // Oversized rows are served through the chunked DAO read path.
        reasoningContent = if (carriesReplay) reasoningContent else reasoningContent?.let { cappedText(it, REASONING_CHAR_BUDGET) },
    )
}
