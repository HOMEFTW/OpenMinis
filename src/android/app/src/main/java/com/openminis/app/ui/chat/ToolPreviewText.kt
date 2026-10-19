package com.openminis.app.ui.chat

/** Budgets apply before scanning, splitting, JSON extraction or text layout on the UI thread. */
internal object ToolPreviewText {
    const val MAX_SCAN_CHARS = 16_384
    const val MAX_LINE_CHARS = 256
    const val MAX_RENDER_CHARS = 4_096
    private const val MAX_ARGUMENT_CHARS = 4_096

    fun heading(text: String): String = if (text.length <= MAX_LINE_CHARS) text else
        text.take(MAX_LINE_CHARS - 1).dropLastWhile { it.isHighSurrogate() } + "…"

    fun tail(text: CharSequence, lines: Int = 12): String {
        if (text.isEmpty() || lines <= 0) return ""
        var end = text.length
        if (text[end - 1] == '\n') {
            end--
            if (end > 0 && text[end - 1] == '\r') end--
        } else if (text[end - 1] == '\r') end--
        val floor = (end - MAX_SCAN_CHARS).coerceAtLeast(0)
        var start = end
        var separators = 0
        while (start > floor) {
            val ch = text[start - 1]
            if (ch == '\n' || ch == '\r') {
                if (++separators >= lines.coerceAtMost(64)) break
                if (ch == '\n' && start > floor + 1 && text[start - 2] == '\r') start--
            }
            start--
        }
        if (start < end && text[start].isLowSurrogate()) start++
        val bounded = text.subSequence(start, end).toString()
        val rendered = bounded.lineSequence().map { line ->
            if (line.length > MAX_LINE_CHARS) "…" + suffix(line, MAX_LINE_CHARS - 1) else line
        }.joinToString("\n")
        val marked = if (start == floor && floor > 0 && !rendered.startsWith("…")) "…$rendered" else rendered
        return if (marked.length <= MAX_RENDER_CHARS) marked else "…" + suffix(marked, MAX_RENDER_CHARS - 1)
    }

    /** Small or incomplete tool-input JSON: only inspect a bounded prefix, never parse the full payload. */
    fun argument(json: String, key: String): String? {
        val head = json.take(MAX_SCAN_CHARS)
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"").find(head) ?: return null
        val out = StringBuilder()
        var i = match.range.last + 1
        while (i < head.length && out.length < MAX_ARGUMENT_CHARS) {
            val ch = head[i++]
            if (ch == '"') return out.toString()
            if (ch != '\\') { out.append(ch); continue }
            if (i >= head.length) break
            when (val escaped = head[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val code = head.substring(i, minOf(i + 4, head.length)).takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code == null) break
                    out.append(code.toChar()); i += 4
                }
                else -> out.append(escaped)
            }
        }
        return out.toString().dropLastWhile { it.isHighSurrogate() } + "…"
    }

    private fun suffix(text: String, count: Int): String {
        var start = (text.length - count).coerceAtLeast(0)
        if (start < text.length && text[start].isLowSurrogate()) start++
        return text.substring(start)
    }
}
