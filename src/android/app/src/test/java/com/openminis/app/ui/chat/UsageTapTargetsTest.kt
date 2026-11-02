package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class UsageTapTargetsTest {
    @Test fun onlyFinalReplyTextGetsABandAndOnlyItsLastFragmentAnchorsIt() {
        val early = AssistantBlock(id = "early", kind = "text", content = "Before tool")
        val tool = AssistantBlock(id = "tool", kind = "tool_use", toolName = "shell_execute")
        val last = AssistantBlock(id = "last", kind = "text", content = "Final reply")
        val message = ChatMessage("reply", "assistant", "", toolBlocks = listOf(early, tool, last))
        fun row(parent: String, index: Int) = FlatChatItem.AssistantMarkdownBlock(
            "reply", parent, "text", index, index == 1, false, "text",
        )
        val first = row("last", 0)
        val second = row("last", 1)
        val targets = usageTapTargets(listOf(message), listOf(row("early", 0), first, second))
        assertEquals(setOf(first.key, second.key), targets.keys)
        assertFalse(targets[first.key]!!.isLastRow)
        assertTrue(targets[second.key]!!.isLastRow)
        assertTrue(usageTapTargets(listOf(message.copy(toolBlocks = listOf(last, tool))), listOf(first, second)).isEmpty())
    }

    @Test fun usageCountersRejectStringAndNegativeValuesWithoutHidingValidContext() {
        assertNull(ChatTokenUsage.parse("{broken"))
        assertNull(ChatTokenUsage.parse("{\"inputTokens\":\"90\",\"outputTokens\":-1}"))
        val usage = ChatTokenUsage.parse("{\"inputTokens\":7,\"outputTokens\":3,\"latestContextTokens\":12}")!!
        assertEquals(7, usage.inputTokens)
        assertEquals(12, usage.latestContextTokens)
    }
}
