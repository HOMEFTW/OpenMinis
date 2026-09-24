package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.InterruptedIOException

/** Keep the wire representation and the plain-text representation in sync. */
internal fun prependContextSummary(message: LLMMessage, summary: String): LLMMessage {
    val parts = message.contentParts.toMutableList()
    if (parts.isNotEmpty()) {
        // Anthropic requires tool results to precede ordinary user text.
        val insertion = parts.indexOfLast { it is AgentContentPart.ToolResult } + 1
        parts.add(insertion, AgentContentPart.Text(summary))
    }
    return message.copy(
        content = summary + "\n\n" + message.content,
        contentParts = parts,
    )
}

internal fun joinCompactSummaries(previous: String?, first: String, second: String): String =
    listOfNotNull(previous?.takeIf { it.isNotBlank() }, first, second).joinToString("\n\n")

/** A budget for starting recovery attempts, not a deadline on healthy streaming. */
internal object StreamRetryPolicy {
    const val RECOVERY_WINDOW_MS = 6 * 60_000L

    fun isTimeout(error: Throwable): Boolean {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause ?: return false
            if (current is InterruptedIOException ||
                current.message.orEmpty().contains("no response from server", ignoreCase = true)) return true
            cause = current.cause?.takeUnless { it === current }
        }
        return false
    }

    fun canStartRecovery(elapsedMs: Long): Boolean = elapsedMs < RECOVERY_WINDOW_MS

    fun canRetrySameProvider(error: Throwable, elapsedMs: Long): Boolean =
        canStartRecovery(elapsedMs) && !isTimeout(error)
}

/** Coalesce growing reasoning before copying it or scheduling a UI update. */
internal class ThinkingUpdateGate {
    private var lastUpdateMs: Long? = null
    private var publishedLength = 0

    fun shouldPublish(nowMs: Long, length: Int, force: Boolean = false): Boolean {
        if (length == publishedLength) return false
        val interval = when {
            length < 2_000 -> 300L
            length < 32_000 -> 500L
            length < 128_000 -> 1_000L
            else -> 2_000L
        }
        if (!force && lastUpdateMs?.let { nowMs - it < interval } == true) return false
        lastUpdateMs = nowMs
        publishedLength = length
        return true
    }

    fun reset() { lastUpdateMs = null; publishedLength = 0 }
}
