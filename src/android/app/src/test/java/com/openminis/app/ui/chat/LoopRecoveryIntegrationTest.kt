package com.openminis.app.ui.chat

import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopConfig
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoopRecoveryIntegrationTest {
    @Test fun `scheduled polls do not accumulate but repeated calls within a fire still do`() {
        val detector = ToolLoopDetector(ToolLoopConfig(historySize = 6, warningThreshold = 2,
            criticalThreshold = 4, globalCircuitBreakerThreshold = 6))
        val args = mapOf<String, Any?>("path" to "/tmp/screen")
        repeat(30) {
            detector.exemptNextCallForScheduledFire()
            assertEquals(Level.NONE, detector.check("file_read", args).level)
            assertEquals(Level.NONE, detector.record("file_read", args, "same screen").level)
        }
        repeat(6) { detector.record("file_read", args, "same screen") }
        assertEquals(Level.CRITICAL, detector.check("file_read", args).level)
    }

    @Test fun `title reminder is model-facing and preserves conversation links and other reminders`() {
        val detector = ToolLoopDetector()
        val output = "minis://session/fixture\n<system-reminder>Keep this context hint.</system-reminder>"
        val reminder = detector.recordToolTitle(JSONObject(), titleDeclared = true)
        assertNotNull(reminder)
        val modelOutput = ToolTitleReminder.appending(output)
        assertTrue(modelOutput.contains(ToolTitleReminder.MARKER))
        assertEquals(output, ToolTitleReminder.stripForDisplay(modelOutput))
        assertNull(detector.recordToolTitle(JSONObject().put("tool_title", "Read screen"), titleDeclared = true))
        assertNull(detector.recordToolTitle(JSONObject(), titleDeclared = false))
    }

    @Test fun `a restored pending question suppresses recovery until its matching answer arrives`() {
        val tail = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
            AgentContentPart.ToolUse("ask_1", "user_ask", JSONObject())))
        assertTrue(InterruptedTailDetector.awaitsUserAnswer(tail, setOf("ask_1")))
        assertFalse(InterruptedTailDetector.awaitsUserAnswer(tail, setOf("other")))
        assertFalse(InterruptedTailDetector.awaitsUserAnswer(tail, emptySet()))
        assertTrue(InterruptedTailDetector.isInterrupted(tail))
        val answered = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult("ask_1", "user_ask", "Selected option")))
        assertFalse(InterruptedTailDetector.awaitsUserAnswer(answered, setOf("ask_1")))
    }

    @Test fun `refusal always discards pending calls even from a provider without an explicit discard flag`() {
        assertTrue(toolTurnMustDiscardCalls("refusal", providerDiscard = false))
        assertTrue(toolTurnMustDiscardCalls("content_filter", providerDiscard = false))
        assertFalse(toolTurnMustDiscardCalls("tool_use", providerDiscard = false))
        assertFalse(toolTurnMustDiscardCalls("tool_calls", providerDiscard = false))
    }
}
