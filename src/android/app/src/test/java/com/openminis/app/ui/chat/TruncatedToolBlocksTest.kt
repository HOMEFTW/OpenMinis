package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TruncatedToolBlocksTest {
    @Test fun truncationPrunesOnlyCurrentTurnToolsBeforePersistence() {
        val blocks = mutableListOf(
            AssistantBlock("old-tool", "tool_use"),
            AssistantBlock("thinking", "thinking", "plan"),
            AssistantBlock("text", "text", "partial reply"),
            AssistantBlock("complete-json", "tool_use", toolArgs = "{}"),
            AssistantBlock("partial-json", "tool_use", toolArgs = "{"),
            AssistantBlock("tail", "text", "tail"),
        )
        discardTruncatedToolBlocks(blocks, 1)
        assertEquals(listOf("old-tool", "thinking", "text", "tail"), blocks.map { it.id })
    }
}
