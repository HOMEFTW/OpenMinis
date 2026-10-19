package com.openminis.app.deeplink

import java.net.URI

/** Local conversation references shared by navigation, the clipboard and agent tools. */
object SessionLink {
    fun create(sessionId: String): String {
        require(validId(sessionId)) { "Invalid session ID" }
        return URI("minis", "session", "/$sessionId", null, null).toASCIIString()
    }

    fun parse(reference: String): String? {
        val value = reference.trim().let {
            when {
                it.startsWith("<") && it.endsWith(">") -> it.substring(1, it.length - 1)
                it.startsWith("[") -> MARKDOWN_LINK.matchEntire(it)?.groupValues?.get(1) ?: it
                else -> it
            }
        }
        if (!value.contains("://")) return value.takeIf(::validId)
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("minis", ignoreCase = true) ||
            !uri.host.equals("session", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val path = uri.rawPath.orEmpty().removeSuffix("/")
        if (!path.startsWith("/") || path.drop(1).contains('/')) return null
        // URI.path decodes percent escapes without turning a literal '+' into a space.
        val decoded = uri.path.removePrefix("/").let {
            if (uri.rawPath.endsWith('/')) it.removeSuffix("/") else it
        }
        return decoded.takeIf(::validId)
    }

    fun requireSessionId(reference: String): String = requireNotNull(parse(reference)) {
        "Expected a local session ID or minis://session/<id> conversation link"
    }

    private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 512 &&
        value == value.trim() && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }

    private val MARKDOWN_LINK = Regex("\\[[^\\]]*]\\(([^\\s]+)\\)")
}
