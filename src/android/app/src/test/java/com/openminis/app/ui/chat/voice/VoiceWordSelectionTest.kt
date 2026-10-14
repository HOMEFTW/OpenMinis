package com.openminis.app.ui.chat.voice

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class VoiceWordSelectionTest {
    @Test
    fun `double tap selects the touched word without replacing transcript text`() {
        val value = TextFieldValue("hello world", TextRange(2), TextRange(0, 5))
        val selected = selectTranscriptWord(value, 8) { TextRange(6, 11) }
        assertEquals("hello world", selected.text)
        assertEquals(TextRange(6, 11), selected.selection)
        assertNull(selected.composition)
        assertEquals("world", selected.text.substring(selected.selection.start, selected.selection.end))
    }

    @Test
    fun `unicode selection uses layout boundaries without splitting surrogate pairs`() {
        val value = TextFieldValue("你好 🌍 world")
        val cjk = selectTranscriptWord(value, 1) { TextRange(0, 2) }
        assertEquals("你好", cjk.text.substring(cjk.selection.start, cjk.selection.end))
        val emoji = selectTranscriptWord(value, 4) { TextRange(3, 5) }
        assertEquals("🌍", emoji.text.substring(emoji.selection.start, emoji.selection.end))
    }

    @Test
    fun `taps beyond text clamp to a valid layout offset and empty text needs no boundary`() {
        val value = TextFieldValue("word")
        var requested = -1
        selectTranscriptWord(value, 100) { requested = it; TextRange(0, 4) }
        assertEquals(3, requested)
        selectTranscriptWord(value, -5) { requested = it; TextRange(0, 4) }
        assertEquals(0, requested)
        val empty = TextFieldValue("")
        assertSame(empty, selectTranscriptWord(empty, 0) { error("Empty layout queried") })
    }

    private fun tracker() = VoiceDoubleTapTracker(40, 300, 20f)

    @Test
    fun `nearby taps select once and a third tap starts another pair`() {
        val tracker = tracker()
        assertFalse(tracker.onTap(100, Offset(10f, 10f)))
        assertTrue(tracker.onTap(220, Offset(12f, 11f)))
        assertFalse(tracker.onTap(320, Offset(12f, 11f)))
        assertTrue(tracker.onTap(420, Offset(12f, 11f)))
    }

    @Test
    fun `too fast too slow or distant taps do not select a word`() {
        for ((interval, secondPosition) in listOf(
            20L to Offset.Zero, 301L to Offset.Zero, 120L to Offset(30f, 0f),
        )) {
            val tracker = tracker()
            assertFalse(tracker.onTap(100, Offset.Zero))
            assertFalse(tracker.onTap(100 + interval, secondPosition))
        }
    }

    @Test
    fun `a drag or long press resets the pending tap pair`() {
        val tracker = tracker()
        assertFalse(tracker.onTap(100, Offset.Zero))
        tracker.reset()
        assertFalse(tracker.onTap(200, Offset.Zero))
    }
}
