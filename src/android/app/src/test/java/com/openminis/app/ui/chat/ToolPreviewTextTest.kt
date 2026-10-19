package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ToolPreviewTextTest {
    @Test fun `one enormous line scans and copies only a bounded suffix`() {
        val source = CountingText("x".repeat(2_000_000) + "LATEST")
        val preview = ToolPreviewText.tail(source)
        assertTrue(preview.endsWith("LATEST"))
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.length <= ToolPreviewText.MAX_LINE_CHARS)
        assertTrue(source.reads <= ToolPreviewText.MAX_SCAN_CHARS + 10)
        assertTrue(source.largestCopy <= ToolPreviewText.MAX_SCAN_CHARS)
    }

    @Test fun `normal tail preserves line order and handles CRLF and trailing newline`() {
        assertEquals("two\nthree", ToolPreviewText.tail("one\r\ntwo\r\nthree\r\n", 2))
        assertEquals("short", ToolPreviewText.tail("short\n"))
        assertEquals("", ToolPreviewText.tail("anything", 0))
    }

    @Test fun `many long lines cannot exceed the render budget or split surrogate pairs`() {
        val preview = ToolPreviewText.tail((1..50).joinToString("\n") { "😀".repeat(1000) }, 50)
        assertTrue(preview.length <= ToolPreviewText.MAX_RENDER_CHARS)
        assertFalse(preview.dropWhile { it == '…' }.first().isLowSurrogate())
        assertFalse(preview.last().isHighSurrogate())
    }

    @Test fun `partial tool JSON remains readable without parsing a giant content field`() {
        val json = "{\"path\": \"report.md\",\"content\":\"" + "x".repeat(2_000_000)
        assertEquals("report.md", ToolPreviewText.argument(json, "path"))
        val body = ToolPreviewText.argument(json, "content")!!
        assertTrue(body.length <= 4097)
        assertTrue(body.endsWith("…"))
    }

    @Test fun `small and incomplete arguments decode standard JSON string escapes`() {
        assertEquals("a\nb\t中文", ToolPreviewText.argument("{\"content\": \"a\\nb\\t\\u4e2d\\u6587\"}", "content"))
        assertEquals("partial…", ToolPreviewText.argument("{\"content\":\"partial", "content"))
        assertNull(ToolPreviewText.argument("{\"other\":\"value\"}", "path"))
    }

    private class CountingText(private val text: String) : CharSequence {
        var reads = 0
        var largestCopy = 0
        override val length get() = text.length
        override fun get(index: Int): Char { reads++; return text[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            largestCopy = maxOf(largestCopy, endIndex - startIndex)
            return text.subSequence(startIndex, endIndex)
        }
    }
}
