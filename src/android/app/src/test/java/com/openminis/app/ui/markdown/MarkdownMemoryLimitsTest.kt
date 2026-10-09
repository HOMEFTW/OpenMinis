package com.openminis.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownMemoryLimitsTest {
    @Test
    fun `input bounds include length line count and CRLF`() {
        assertTrue(MarkdownWorkLimits.canParseBlocks("a".repeat(8_000)))
        assertFalse(MarkdownWorkLimits.canParseBlocks("a".repeat(8_001)))
        assertTrue(MarkdownWorkLimits.canParseBlocks("a\r\n".repeat(4_095)))
        assertFalse(MarkdownWorkLimits.canParseBlocks("a\n".repeat(4_096)))
        assertFalse(MarkdownWorkLimits.canParseBlocks("a\n".repeat(64_001)))
    }

    @Test
    fun `million character full view uses bounded chunks and preserves Unicode`() {
        val text = "a".repeat(3_999) + "😀" + "b\n".repeat(1_000_000)
        val chunks = (0 until MarkdownWorkLimits.plainTextChunkCount(text)).map {
            MarkdownWorkLimits.plainTextChunk(text, it)
        }
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= MarkdownWorkLimits.DISPLAY_CHUNK_CHARS + 1 })
        assertTrue(chunks.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals(0, MarkdownWorkLimits.plainTextChunkCount(""))
    }

    @Test
    fun `oversized entry is not retained and does not evict small entries`() {
        val cache = MarkdownCache<String, String>(budget = 5, sizer = { _, v -> v.length })
        cache["small"] = "abc"
        cache["huge"] = "123456"
        assertNull(cache["huge"])
        assertEquals("abc", cache["small"])
        assertEquals(3L, cache.totalChars)
    }

    @Test
    fun `access refreshes recency and replacement removal and clear update weight`() {
        val cache = MarkdownCache<String, String>(budget = 6, sizer = { _, v -> v.length })
        cache["a"] = "aaa"
        cache["b"] = "bbb"
        assertEquals("aaa", cache["a"])
        cache["c"] = "ccc"
        assertNull(cache["b"])
        cache["a"] = "a"
        assertEquals(4L, cache.totalChars)
        cache.remove("c")
        assertEquals(1L, cache.totalChars)
        cache.clear()
        assertEquals(0L, cache.totalChars)
        assertEquals(0, cache.size)
    }

    @Test
    fun `empty and tiny entries have an entry bound`() {
        val cache = MarkdownCache<Int, String>(budget = 100, maxEntries = 3, sizer = { _, v -> v.length })
        repeat(1_000) { cache[it] = "" }
        assertEquals(3, cache.size)
        assertEquals(3L, cache.totalChars)
        assertNull(cache[996])
        assertEquals("", cache[999])
    }

    @Test
    fun `weights cannot overflow Int to defeat eviction`() {
        val cache = MarkdownCache<String, String>(budget = Int.MAX_VALUE, sizer = { _, _ -> Int.MAX_VALUE })
        cache["a"] = "a"
        cache["b"] = "b"
        assertEquals(1, cache.size)
        assertEquals(Int.MAX_VALUE.toLong(), cache.totalChars)
    }
}
