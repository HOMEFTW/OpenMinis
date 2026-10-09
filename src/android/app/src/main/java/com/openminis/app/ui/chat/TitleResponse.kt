package com.openminis.app.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class TitleResponse(val title: String, val category: String?, val folder: String?)

/** A title request promises JSON. Never turn a partial response or explanation into a title. */
internal fun parseGeneratedTitle(text: String, stopReason: String? = null): TitleResponse? {
    if (stopReason in setOf("length", "max_tokens", "incomplete")) return null
    val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val obj = try { Json.parseToJsonElement(cleaned) as? JsonObject } catch (_: Exception) { null }
        ?: return null
    fun string(key: String): String? = (obj[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val title = string("title")?.replace(Regex("\\s+"), " ") ?: return null
    if (!title.any { it.isLetterOrDigit() } || title.equals("null", true) || title == "New Chat") return null
    val categories = setOf("code", "writing", "research", "analysis", "creative", "chat", "math",
        "translation", "health", "finance", "travel", "education", "design", "productivity", "support", "other")
    return TitleResponse(title.take(80), string("category")?.takeIf { it in categories },
        string("folder")?.takeUnless { it.equals("null", true) })
}
