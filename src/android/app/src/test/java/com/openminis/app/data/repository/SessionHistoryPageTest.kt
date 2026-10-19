package com.openminis.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class SessionHistoryPageTest {
    private fun row(id: String, text: String) = MessagePageItem(id, "assistant", 1, text)

    @Test fun `a surrogate pair at the budget boundary is deferred intact`() {
        val rows = listOf(row("first", "abc"), row("emoji", "🙂"))
        val first = SessionHistoryPage.build(rows, 0, 0, 2, maxChars = 4)
        assertEquals(1, first.getJSONArray("messages").length())
        assertEquals(1, first.getInt("next_offset"))
        assertEquals(0, first.getInt("next_text_offset"))
        assertTrue(first.getBoolean("has_more"))
        val second = SessionHistoryPage.build(rows.drop(1), 1, 0, 2, maxChars = 4)
        assertEquals("🙂", second.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertFalse(second.getBoolean("has_more"))
    }

    @Test fun `a partial final message keeps has more even without another database row`() {
        val rows = listOf(row("long", "abcdef"))
        val first = SessionHistoryPage.build(rows, 0, 0, 1, maxChars = 4)
        assertEquals("abcd", first.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(0, first.getInt("next_offset"))
        assertEquals(4, first.getInt("next_text_offset"))
        assertTrue(first.getBoolean("has_more"))
        val last = SessionHistoryPage.build(rows, 0, 4, 1, maxChars = 4)
        assertEquals("ef", last.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(1, last.getInt("next_offset"))
        assertFalse(last.getBoolean("has_more"))
    }

    @Test fun `invalid within-message cursors cannot silently cut or discard characters`() {
        val rows = listOf(row("unicode", "🙂x"))
        assertThrows(IllegalArgumentException::class.java) { SessionHistoryPage.build(rows, 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { SessionHistoryPage.build(rows, 0, 4, 1) }
        assertThrows(IllegalArgumentException::class.java) { SessionHistoryPage.build(emptyList(), 0, 1, 0) }
    }
}
