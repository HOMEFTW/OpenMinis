package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.io.BufferedReader

/** Decode SSE data frames, including comments, optional spaces and multiline data.
 * Some relays omit the final blank line; expose that EOF frame for compatibility.
 * It is NOT a completion signal: providers still require their message-level terminal event.
 */
internal class SseEventReader(private val reader: BufferedReader) {
    private var firstLine = true
    fun nextData(): String? {
        val data = mutableListOf<String>()
        while (true) {
            var line = reader.readLine() ?: return data.takeIf { it.isNotEmpty() }?.joinToString("\n")
            if (firstLine) { line = line.removePrefix("\uFEFF"); firstLine = false }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return data.joinToString("\n")
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            if (field != "data") continue
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            data.add(value)
        }
    }
}

/** Never repair incomplete executable input or accept JSONObject's permissive trailing text. */
internal fun parseToolInputObject(raw: String): JSONObject = try {
    val value = Json.parseToJsonElement(raw)
    require(value is JsonObject) { "Expected a complete JSON object" }
    validateJsonLiterals(value)
    JSONObject(value.toString())
} catch (error: Exception) {
    throw LLMError.DecodingError(error)
}

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
private fun validateJsonLiterals(value: JsonElement) {
    when (value) {
        is JsonObject -> value.values.forEach(::validateJsonLiterals)
        is JsonArray -> value.forEach(::validateJsonLiterals)
        is JsonPrimitive -> require(value.isString || value is JsonNull ||
            value.content == "true" || value.content == "false" || jsonNumber.matches(value.content)) {
            "Invalid JSON literal"
        }
    }
}
