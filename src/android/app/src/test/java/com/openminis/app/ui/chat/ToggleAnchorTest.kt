package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-user-ask-resolved-expand] An answered user_ask card (and a folded tool
 * row) the user opens or shuts keeps its TOP in place, and the card's icon and
 * chevron stay on its title and status lines (iOS e563158e2).
 */
class ToggleAnchorTest {

    /**
     * The reverseLayout list in one dimension, screen y growing downward: the
     * card's BOTTOM is pinned at [pinnedBottom] by the list, and a scroll of
     * `s` (LazyListState units: + moves content down) moves everything by +s.
     */
    private class ReverseList(var pinnedBottom: Float) {
        fun top(height: Int) = pinnedBottom - height
        fun scroll(s: Float) { pinnedBottom += s }
    }

    @Test fun `an expand and a collapse keep the top fixed on every animation frame`() {
        for ((from, to) in listOf(58 to 312, 312 to 58, 58 to 59, 400 to 400)) {
            val list = ReverseList(pinnedBottom = 900f)
            val top0 = list.top(from)
            var current = from
            for (frame in 0..20) {
                val h = ToggleAnchorMath.heightAt(from, to, frame / 20f)
                list.scroll(ToggleAnchorMath.keepTopScroll(current, h))
                current = h
                assertEquals("$from->$to frame $frame", top0, list.top(h), 0.001f)
            }
            assertEquals(to, current)
        }
        // Without the compensation the card grows upward: the reported bug.
        val list = ReverseList(900f)
        assertEquals(900f - 312, list.top(312), 0f)
        assertTrue(list.top(312) < list.top(58))
    }

    @Test fun `six open-shut cycles do not drift`() {
        val list = ReverseList(700f)
        val top0 = list.top(58)
        var h = 58
        repeat(6) {
            for (target in listOf(212, 58)) {
                for (f in 1..15) {
                    val next = ToggleAnchorMath.heightAt(if (target == 212) 58 else 212, target, f / 15f)
                    list.scroll(ToggleAnchorMath.keepTopScroll(h, next))
                    h = next
                }
            }
        }
        assertEquals(top0, list.top(h), 0.001f)
    }

    @Test fun `the animation curve starts, ends and never overshoots`() {
        assertEquals(58, ToggleAnchorMath.heightAt(58, 312, 0f))
        assertEquals(312, ToggleAnchorMath.heightAt(58, 312, 1f))
        var prev = 58
        for (i in 1..100) {
            val h = ToggleAnchorMath.heightAt(58, 312, i / 100f)
            assertTrue(h in prev..312)
            prev = h
        }
    }

    @Test fun `reveal after an expand - bottom into view, the top never pushed out, nothing when it fits`() {
        // Fits: no scroll.
        assertEquals(0f, ToggleAnchorMath.revealShift(100f, 400f, 0f, 800f), 0f)
        // Bottom 150 px under the visible bottom, plenty of room above: shift 150.
        assertEquals(150f, ToggleAnchorMath.revealShift(300f, 950f, 0f, 800f), 0f)
        // Taller than the room above: stop with the top at the visible top.
        assertEquals(60f, ToggleAnchorMath.revealShift(60f, 1500f, 0f, 800f), 0f)
        // Top already above the visible top: never scroll it further out.
        assertEquals(0f, ToggleAnchorMath.revealShift(-20f, 1500f, 0f, 800f), 0f)
    }

    @Test fun `a fold row's index and offset are restored across the rows it inserts`() {
        // 10 flat rows, 1 leading item (resume banner); the group row is flat #6.
        val leading = ToggleAnchorMath.leadingCount(lazyIndex = 4, flatPos = 6, flatSize = 10)
        assertEquals(1, leading)
        assertEquals(4, ToggleAnchorMath.lazyIndex(6, 10, leading))
        // Opening inserts its 3 calls after it (newer, so LOWER lazy indices):
        // the row itself moves to a higher index.
        assertEquals(7, ToggleAnchorMath.lazyIndex(6, 13, leading))
        // Requested at -offset, an item at scroll position (i, s) has offset -s.
        for (offset in listOf(0, 37, 640)) {
            assertEquals(offset, -ToggleAnchorMath.restoreScrollOffset(offset))
        }
    }

    // ── Source guards ──────────────────────────────────────────────────────

    private val frame by lazy { ProductionSources.read("ui/chat/TaskCardFrame.kt") }
    private val card by lazy { ProductionSources.read("ui/chat/UserQuestionHistoryBlock.kt") }
    private val screen by lazy { ProductionSources.read("ui/chat/ChatScreen.kt") }
    private val anchor by lazy { ProductionSources.read("ui/chat/ToggleAnchor.kt") }

    @Test fun `the card headers stay centred while expanded answers go below them`() {
        val f = frame.substringAfter("internal fun TaskCardFrame(").substringBefore("\n}\n")
        assertTrue(f.contains("below: (@Composable ColumnScope.() -> Unit)? = null,"))
        assertTrue(f.contains("TaskCardHeadRow(accent, icon, action, content)"))
        assertTrue("indented to the text column (iOS leading 46)", f.contains("padding(start = TaskCardMetrics.iconDisc + TaskCardMetrics.iconGap, top = TaskCardMetrics.belowGap)"))
        assertFalse("the outer box is not a centring Row any more", f.contains("verticalAlignment = Alignment.CenterVertically"))
        val head = frame.substringAfter("private fun TaskCardHeadRow(").substringBefore("\n}\n")
        assertTrue(head.contains("verticalAlignment = Alignment.CenterVertically"))
        // The fork uses its own full-width header Row, followed by an answer
        // Column, rather than TaskCardFrame's optional `below` slot.
        val resolved = card.substringAfter("internal fun UserQuestionHistoryBlock(")
        assertTrue("answers have their own expanded column", resolved.contains("        if (expanded) Column("))
        val header = resolved.substringAfter("        Row(").substringBefore("        if (expanded) Column(")
        assertTrue(header.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(header.contains("Text(ToolPreviewText.heading("))
        assertTrue(header.contains("R.string.user_ask_answered"))
        assertTrue(header.contains("Icon(if (expanded)"))
        assertTrue("the header closes before the answer column", header.trimEnd().endsWith("}"))
        val answers = resolved.substringAfter("        if (expanded) Column(")
        assertTrue(answers.contains("for (question in history.request.questions)"))
        assertTrue(answers.contains("history.answers[question.id]"))
    }

    @Test fun `the answered card animates its own height with the top anchored`() {
        val resolved = card.substringAfter("internal fun UserQuestionHistoryBlock(")
        assertTrue(resolved.contains("val resize = remember(block.id) { TopAnchoredResize() }"))
        assertTrue(resolved.contains("val anchor = LocalChatToggleAnchor.current"))
        assertTrue(resolved.contains(".clickable { resize.toggle(scope, anchor) { expanded = !expanded } }"))
        assertTrue(resolved.contains("Modifier.fillMaxWidth().then(resize.modifier).onGloballyPositioned { resize.coords = it }"))
        assertFalse("animateContentSize resizes during measure; the scroll would lag a frame", card.contains("animateContentSize"))
        // Scroll and height set in the same frame callback.
        val loop = anchor.substringAfter("val done = withFrameNanos { now ->").substringBefore("t >= 1f\n")
        assertTrue(loop.indexOf("anchor?.keepTop(current, h)") in 0 until loop.indexOf("shown = h"))
        assertTrue(anchor.contains("if (d != 0f) listState.dispatchRawDelta(d)"))
    }

    @Test fun `the chat list provides the anchor, and a fold row is captured before it toggles`() {
        // The fork provides the anchor around the whole Scaffold, including
        // the historical question card, instead of wrapping that row alone.
        val provider = screen.indexOf("LocalChatToggleAnchor provides toggleAnchor,")
        val question = screen.indexOf("UserQuestionHistoryBlock(item.block,")
        assertTrue("the historical question card inherits the list anchor", provider in 0 until question)
        val tap = screen.substringAfter("is FlatChatItem.AssistantToolGroup -> ToolGroupRow(item) {").substringBefore("\n                            }")
        assertTrue(tap.indexOf("toggleAnchor.captureRow(item.key, flatItems)") in 0 until tap.indexOf("toolFold.toggle(item.runKey)"))
        val publish = screen.substringAfter("rawFlatItems = toolFold.fold(").substringBefore("val liveGrowId")
        assertTrue("requested for the same remeasure that applies the rows", publish.contains("toggleAnchor.restoreRow(rawFlatItems)"))
        assertTrue(anchor.contains("listState.requestScrollToItem(index, ToggleAnchorMath.restoreScrollOffset(p.offset))"))
    }

    @Test fun `the bottom drift snap stands down while a toggle moves the list`() {
        // [T-toggle-anchor-snap-yield] Pinned to the bottom, LAYOUT-DRIFT-SNAP
        // re-pinned after every compensation step, a frame late.
        val snap = screen.substringBefore("tracedScrollToItem(\"LAYOUT-DRIFT-SNAP\", 0, 0)").takeLast(2500)
        assertTrue(snap.contains("if (toggleAnchor.isBusy) return@collectLatest"))
        assertTrue("the anchor exists before the snap effect", screen.indexOf("val toggleAnchor = remember(listState)") < screen.indexOf("tracedScrollToItem(\"LAYOUT-DRIFT-SNAP\", 0, 0)"))
        for (step in listOf("fun keepTop(oldHeight: Int, newHeight: Int) {\n        markBusy()", "markBusy()\n        listState.requestScrollToItem", "anchor?.markBusy(durationMs + 450)")) {
            assertTrue(step, anchor.contains(step))
        }
    }
}
