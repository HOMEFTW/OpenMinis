package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolStreamAttemptTest {
    @Test fun retryAfterUnfinishedCallKeepsStartAndCompletionPaired() {
        val ids = ToolStreamAttemptIds()
        assertEquals("call_demo", ids.start("call_demo"))
        ids.reset()
        assertEquals("call_demo", ids.start("call_demo"))
        assertEquals("call_demo", ids.input("call_demo"))
        assertEquals("call_demo", ids.complete("call_demo"))
    }

    @Test fun duplicatesAndExistingSuffixesRemainUniqueAndPaired() {
        val ids = ToolStreamAttemptIds()
        val raw = listOf("call_x", "call_x", "call_x-2", "previous", "x".repeat(64), "x".repeat(64))
        val started = raw.map(ids::start)
        assertEquals(started.size, started.toSet().size)
        assertEquals(started, raw.map(ids::complete))
    }

    @Test fun compositeResponsesIdentifiersAreNotTruncatedByTheUi() {
        val ids = ToolStreamAttemptIds()
        val raw = "call_" + "a".repeat(40) + "|fc_" + "b".repeat(40)
        assertEquals(raw, ids.start(raw))
        assertEquals(raw, ids.complete(raw))
    }

    @Test fun fallbackRemovesFailedToolsAndPreservesPreviousTurnBoundary() {
        val blocks = mutableListOf(AssistantBlock("old-info", "info"), AssistantBlock("old-tool", "tool_use"))
        val boundary = blocks.size
        blocks.add(AssistantBlock("failed-tool", "tool_use"))
        blocks.add(AssistantBlock("failed-thinking", "thinking"))
        rollbackToolStreamAttempt(blocks, boundary)
        val nextBoundary = replaceFallbackBanner(blocks, AssistantBlock("fallback", "info"))
        blocks.add(AssistantBlock("new-text", "text", "ok"))
        assertEquals(listOf("fallback", "old-tool", "new-text"), blocks.map { it.id })
        assertEquals(listOf("new-text"), blocks.drop(nextBoundary).map { it.id })
    }

    @Test fun missingFinishAndOutputLimitsDiscardCalls() {
        for (reason in listOf(null, "", " ", "length", "max_tokens", "max_output_tokens")) {
            assertTrue(toolTurnMustDiscardCalls(reason, false))
        }
        assertTrue(toolTurnMustDiscardCalls("end_turn", true))
        assertFalse(toolTurnMustDiscardCalls("tool_calls", false))
        assertFalse(toolTurnMustDiscardCalls("tool_use", false))
    }

    @Test fun nullSignaturesNeverBecomeLiteralNull() {
        val value = JSONObject().put("null", JSONObject.NULL).put("literal", "null")
            .put("blank", " ").put("number", 42).put("signature", "opaque==")
        for (key in listOf("null", "literal", "blank", "number", "missing")) {
            assertNull(value.optionalNonBlankString(key))
        }
        assertEquals("opaque==", value.optionalNonBlankString("signature"))
    }
}
