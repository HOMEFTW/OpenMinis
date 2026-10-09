package com.openminis.app.ui.markdown

/** Bounds rich parsing work; callers keep the original text when falling back. */
internal object MarkdownWorkLimits {
    const val MAX_BLOCK_CHARS = 128_000
    const val MAX_INLINE_CHARS = 8_000
    const val MAX_LINES = 4_096
    const val MAX_NESTING = 16
    const val DISPLAY_CHUNK_CHARS = 4_000

    fun plainTextChunkCount(text: String): Int =
        if (text.isEmpty()) 0 else (text.length - 1) / DISPLAY_CHUNK_CHARS + 1

    /** Lazy full-text viewing, without splitting a Unicode surrogate pair. */
    fun plainTextChunk(text: String, index: Int): String {
        require(index in 0 until plainTextChunkCount(text))
        var start = index * DISPLAY_CHUNK_CHARS
        var end = minOf(text.length, start + DISPLAY_CHUNK_CHARS)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        return text.substring(start, end)
    }

    fun canParseBlocks(text: String): Boolean {
        if (text.length > MAX_BLOCK_CHARS) return false
        var lineChars = 0
        var lines = 1
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                lineChars = 0
                if (ch != '\n' || i == 0 || text[i - 1] != '\r') lines++
                if (lines > MAX_LINES) return false
            } else if (++lineChars > MAX_INLINE_CHARS) {
                return false
            }
        }
        return true
    }
}
