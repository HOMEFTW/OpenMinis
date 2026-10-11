package com.openminis.app.data.repository

import com.openminis.app.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.json.JSONObject

// Android's ICU rejects an unescaped closing brace, even though desktop Java accepts it.
private val legacyToolEnd = Regex("\"isOffloadedArgument\"\\s*:\\s*(?:true|false)\\s*\\}$")

/** Recover only the missing outer toolUse brace emitted by the old turn writer. */
internal fun repairLegacyAssistantPartsJson(raw: String): String? {
    if (!raw.contains("\"isOffloadedArgument\"")) return null
    val out = StringBuilder(raw.length)
    var quoted = false
    var escaped = false
    var objects = 0
    var arrays = 0
    var partStart = -1
    var changed = false
    for (i in raw.indices) {
        val ch = raw[i]
        out.append(ch)
        if (quoted) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> quoted = false
            }
            continue
        }
        when (ch) {
            '"' -> quoted = true
            '[' -> arrays++
            ']' -> arrays--
            '{' -> {
                if (objects == 0 && arrays == 1) partStart = i
                objects++
            }
            '}' -> {
                objects--
                if (objects != 1 || arrays != 1 || partStart < 0) continue
                var next = i + 1
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (raw.getOrNull(next) == ',') {
                    next++
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (raw.getOrNull(next) != '{') continue
                } else if (raw.getOrNull(next) != ']') continue

                val fragment = raw.substring(partStart, i + 1)
                if (!legacyToolEnd.containsMatchIn(fragment)) continue
                val candidate = runCatching { JSONObject("$fragment}") }.getOrNull() ?: return null
                val value = candidate.optJSONObject("value") ?: return null
                if (candidate.length() != 2 || candidate.optString("type") != "toolUse" ||
                    value.opt("isOffloadedArgument") !is Boolean ||
                    listOf("toolUseId", "name", "input", "description", "pageURL", "imageFilePath")
                        .any { value.opt(it) !is String } || !value.has("thoughtSignature")) return null
                out.append('}')
                objects--
                changed = true
            }
        }
    }
    if (!changed) return null
    val repaired = out.toString()
    // Never return a partial repair, invent arguments, or trim damaged content.
    return repaired.takeIf { runCatching { Json.parseToJsonElement(it) is JsonArray }.getOrDefault(false) }
}

/** Read-time recovery keeps original on-disk bytes and all row metadata intact. */
internal fun MessageEntity.withReadableAssistantParts(): MessageEntity {
    if (!role.equals("assistant", ignoreCase = true)) return this
    val repaired = repairLegacyAssistantPartsJson(partsJson) ?: return this
    return copy(partsJson = repaired)
}

/** Shared by projections and exporters that do not carry a MessageEntity role. */
internal fun readablePartsJson(raw: String): String = repairLegacyAssistantPartsJson(raw) ?: raw
