package com.openminis.app.ui.chat

import com.openminis.app.ui.markdown.MarkdownWorkLimits
import org.junit.Assert.*
import org.junit.Test

class MarkdownMemoryRiskTest {
    @Test(timeout = 2_000)
    fun `giant block input keeps raw source without splitting or inline math`() {
        val text = "![" + "[".repeat(2_000_000) + "tail"
        assertEquals(listOf(text), debugParseBlockRaws(text))
        assertEquals(listOf(text), debugParseIncrementalRaws(listOf("start", text)))
        assertTrue(collectInlineMathLatex(text).isEmpty())
    }

    @Test
    fun `edits before the old fingerprint window invalidate the frozen prefix`() {
        val prefix = "alpha " + "a".repeat(1_000) + "\n\n"
        val original = prefix + "live"
        val edited = "omega " + original.substring(6) + " tail"
        assertEquals(debugParseBlockRaws(edited), debugParseIncrementalRaws(listOf(original, edited)))
    }

    @Test
    fun `incremental and complete parsing agree for normal append and shorter replacement`() {
        val final = "# Header\n\nfirst\n\nsecond"
        assertEquals(debugParseBlockRaws(final), debugParseIncrementalRaws(listOf("# Head", "# Header\n\nfirst", final)))
        assertEquals(debugParseBlockRaws("short"), debugParseIncrementalRaws(listOf(final, "short")))
    }

    @Test(timeout = 2_000)
    fun `quote nesting has a finite depth`() {
        val source = "> ".repeat(1_000) + "tail"
        assertEquals(listOf(source), debugParseBlockRaws(source))
        assertTrue(MarkdownWorkLimits.canParseBlocks(source))
    }
}
