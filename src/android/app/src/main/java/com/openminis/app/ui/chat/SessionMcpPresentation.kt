package com.openminis.app.ui.chat

import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.logging.LogRedactor
import java.net.URI

/** Endpoint paths and arbitrary query fields can carry credentials, so show only the origin. */
internal fun sessionMcpEndpoint(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val hidden = !uri.rawUserInfo.isNullOrEmpty() ||
            !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/" ||
            uri.rawQuery != null || uri.rawFragment != null
        "$scheme://$host$port${if (hidden) "/…" else ""}"
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

/** A command may embed a shell script; only an unambiguous executable basename is safe to show. */
internal fun sessionMcpCommand(command: String?): String? {
    val value = command?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
    val executable = value.substringAfterLast('/').substringAfterLast('\\')
    return executable.takeIf { it.matches(Regex("[\\p{L}\\p{N}_.+-]+")) }
}

internal fun sessionMcpDisplayText(server: MCPRepository.MCPServerConfig, text: String): String {
    val values = server.headers.values + server.env.values + server.args
    // LogRedactor intentionally ignores short known secrets in general logs.
    // A read-only config view also needs to hide short, explicitly configured values.
    return values.filter { it.isNotBlank() && it.length < 4 }.distinct().sortedByDescending { it.length }
        .fold(LogRedactor.redact(text, values)) { redacted, value -> redacted.replace(value, "••••") }
}

internal fun sessionMcpSummary(server: MCPRepository.MCPServerConfig): String =
    if (server.isStdio) {
        listOfNotNull("STDIO", sessionMcpCommand(server.command)?.let { sessionMcpDisplayText(server, it) })
            .joinToString(" · ")
    } else {
        sessionMcpEndpoint(server.url)?.let { sessionMcpDisplayText(server, it) } ?: "HTTP"
    }
