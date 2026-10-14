package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellUnicodeOutputTest {
    private fun assertValidUtf8RoundTrip(text: String) {
        assertEquals(text, text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test fun boundedHeadAndTailNeverRetainHalfOfAnEmoji() {
        for (keep in 1..5) {
            val text = "ab🙂0123456789🙂z"
            val output = BoundedShellOutput(keep).append(text).toString()
            assertValidUtf8RoundTrip(output)
            assertTrue(output.contains("characters omitted"))
        }
    }

    @Test fun smallOutputKeepsEmojiSplitBetweenAppendsAndRetentionBuffers() {
        val text = "中🙂"
        val output = BoundedShellOutput(2)
        for (char in text) output.append(char)
        assertEquals(text, output.toString())
        assertValidUtf8RoundTrip(output.toString())
    }

    @Test fun stderrLineLimitKeepsAnEmojiIntact() {
        val text = "a".repeat(16_383) + "🙂尾\n"
        val output = StderrDrain(text.byteInputStream(Charsets.UTF_8)) {}.start().finish(2000)
        assertValidUtf8RoundTrip(output)
        assertTrue(output.contains("🙂"))
        assertTrue(output.contains("尾"))
    }

    @Test fun finalSanitizerTruncationPreservesCompleteCharactersAtBothEdges() {
        val text = "ab🙂0123456789🙂z"
        for (limit in 0..10) {
            val output = TerminalSanitizer.truncateIfNeeded(text, limit)
            assertValidUtf8RoundTrip(output)
            val parts = output.split(Regex("\\n\\n\\[\\.\\.\\. \\d+ characters omitted \\.\\.\\.\\]\\n\\n"))
            assertEquals(2, parts.size)
            val omitted = text.length - parts[0].length - parts[1].length
            assertTrue(output.contains("$omitted characters omitted"))
        }
    }
}
