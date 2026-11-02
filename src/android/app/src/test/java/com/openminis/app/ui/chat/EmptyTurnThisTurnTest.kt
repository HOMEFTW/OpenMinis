package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-empty-turn-this-turn] The empty-turn gate judges THIS turn, not the
 * whole bubble. Field case (DeepSeek, sid 41295a4c turn 45): a reasoning-only
 * `stop` in a bubble that already held earlier narration and 40+ tool calls was
 * counted as "has content", so neither the reminder retry nor the hint fired
 * and the run ended silently.
 */
class EmptyTurnThisTurnTest {

    private fun text(id: String, s: String) = AssistantBlock(id = id, kind = "text", content = s)
    private fun tool(id: String) = AssistantBlock(id = id, kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS)
    private fun thinking(id: String) = AssistantBlock(id = id, kind = "thinking", content = "plan the next part")

    // Earlier turns of the same bubble: narration plus tool calls.
    private val earlier = listOf(text("t0", "继续写第二部分"), tool("u1"), tool("u2"), thinking("k1"))

    @Test
    fun `a reasoning-only stop after earlier turns is empty`() {
        val blocks = earlier + thinking("k2")
        assertFalse(EmptyTurnRecovery.turnHasText("", blocks, turnStartBlockIndex = earlier.size))
        assertTrue(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "stop"))
    }

    @Test
    fun `a final summary after earlier tool calls is not empty`() {
        // The normal end of a task: tools ran in earlier turns, this turn answers.
        assertTrue(EmptyTurnRecovery.turnHasText("全部完成，计划表已写入 docs/plan。", earlier, earlier.size))
        // Text that only reached the block list (no streamed turnText) counts too.
        val blocks = earlier + text("t9", "Done.")
        assertTrue(EmptyTurnRecovery.turnHasText("", blocks, earlier.size))
    }

    @Test
    fun `whitespace is not text, and earlier text is not this turn's`() {
        assertFalse(EmptyTurnRecovery.turnHasText("  \n", earlier + text("t9", "   "), earlier.size))
        assertTrue("the whole bubble would have said yes", EmptyTurnRecovery.turnHasText("", earlier, 0))
    }

    @Test
    fun `an out-of-range start index is safe`() {
        assertFalse(EmptyTurnRecovery.turnHasText("", earlier, earlier.size + 5))
        assertTrue(EmptyTurnRecovery.turnHasText("", earlier, -1))
    }
}
