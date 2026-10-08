package com.openminis.app.data

/**
 * Diagnostic byte/token cross-check for media accidentally expanded as text by a relay.
 * This does not replace the local 100 MiB image budget or reject structured image inputs.
 */
object PayloadSizeAudit {
    const val SUSPICIOUS_BYTES_PER_TOKEN = 64
    const val MIN_BYTES_TO_AUDIT = 32 * 1024
    const val DANGEROUS_WINDOW_FRACTION = 0.5

    data class Finding(
        val bytes: Int,
        val estimatedTokens: Int,
        val bytesPerToken: Int,
        val suspicious: Boolean,
        val dangerous: Boolean,
    )

    /** UTF-8 size without allocating a second copy of a potentially huge payload. */
    fun utf8Bytes(text: String): Int {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i < text.length && text[i].isLowSurrogate() -> { i++; 4 }
                c.isSurrogate() -> 1 // JVM UTF-8 encoder replaces malformed surrogates with '?'.
                else -> 3
            }
        }
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun audit(bytes: Int, estimatedTokens: Int, contextWindowTokens: Int): Finding {
        if (bytes < MIN_BYTES_TO_AUDIT) return Finding(bytes, estimatedTokens, 0, false, false)
        val ratio = bytes / estimatedTokens.coerceAtLeast(1)
        return Finding(bytes, estimatedTokens, ratio,
            suspicious = ratio >= SUSPICIOUS_BYTES_PER_TOKEN,
            dangerous = contextWindowTokens > 0 && bytes.toLong() >= contextWindowTokens * DANGEROUS_WINDOW_FRACTION,
        )
    }
}
