package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class LongConversationPolicyTest {
    @Test fun oldReasoningIsTrimmedOldestFirstWithoutChangingStoredHistory() {
        val history = (0..7).map { i -> LLMMessage(
            if (i == 3) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,
            "message-$i", reasoningContent = if (i == 3) null else "thought-$i",
        ) }
        val trimmed = trimOldReasoning(history, 1) { it.length }
        assertEquals("", trimmed[0].reasoningContent)
        assertEquals(history.drop(1), trimmed.drop(1))
        assertEquals("thought-0", history[0].reasoningContent)
        val allOld = trimOldReasoning(history, 1000) { it.length }
        assertTrue(allOld.take(3).all { it.reasoningContent == "" })
        assertEquals(history.drop(3), allOld.drop(3))
    }

    @Test fun currentToolCycleAndLatestFourMessagesKeepReasoning() {
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "question")) +
            (0..7).map { LLMMessage(LLMMessage.Role.ASSISTANT, "", reasoningContent = "current") }
        assertEquals(history, trimOldReasoning(history, 1000) { it.length })
        val lastUserAtEnd = history + LLMMessage(LLMMessage.Role.USER, "next")
        val trimmed = trimOldReasoning(lastUserAtEnd, 1000) { it.length }
        assertEquals(lastUserAtEnd.takeLast(4), trimmed.takeLast(4))
        assertEquals(history, trimOldReasoning(history, 0) { it.length })
    }

    @Test fun certificateFailureNeverRetriesButConnectionResetCan() {
        val cert = RuntimeException(javax.net.ssl.SSLHandshakeException("handshake").apply {
            initCause(java.security.cert.CertificateException("invalid certificate"))
        })
        assertTrue(com.openminis.app.data.model.LLMError.isCertificateFailure(cert))
        assertFalse(StreamRetryPolicy.canRetrySameProvider(cert, 1000))
        assertTrue(StreamRetryPolicy.canRetrySameProvider(java.io.IOException("connection reset"), 1000))
    }

    @Test fun summaryAppearsInActualChatAndResponsesPayloads() {
        val message = prependContextSummary(LLMMessage(LLMMessage.Role.USER, "question",
            contentParts = listOf(AgentContentPart.Text("question"))), "preserved-old-decision")
        for (responses in listOf(false, true)) {
            val provider = OpenAIProvider("test", LLMModel.gpt4oMini,
                basePath = "https://example.invalid/v1", useResponsesAPI = responses)
            val body = (if (responses) provider.buildResponsesAPIBody(
                messages = listOf(message), systemPrompt = null, maxTokens = 1024, stream = false,
            ) else provider.buildRequestBody(messages = listOf(message), systemPrompt = null,
                maxTokens = 1024, stream = false, temperature = null, imageParts = emptyList())).toString()
            assertTrue(body.contains("preserved-old-decision"))
            assertTrue(body.contains("question"))
        }
    }
    @Test fun summarySurvivesStructuredUserAndToolResultMessages() {
        for (parts in listOf(
            listOf(AgentContentPart.Text("new question")),
            listOf(AgentContentPart.ToolResult("call", "read", "result")),
            listOf(AgentContentPart.ImageData(byteArrayOf(1), "image/png")),
        )) {
            val original = LLMMessage(LLMMessage.Role.USER, "question", contentParts = parts)
            val result = prependContextSummary(original, "old decisions")
            val summary = AgentContentPart.Text("old decisions")
            assertTrue(result.contentParts.contains(summary))
            assertEquals(parts, result.contentParts.filterNot { it == summary })
            if (parts.first() is AgentContentPart.ToolResult) assertEquals(parts.first(), result.contentParts.first())
            assertEquals(parts, original.contentParts)
        }
    }

    @Test fun summarySurvivesLegacyTextAndRecursiveSplitting() {
        val original = LLMMessage(LLMMessage.Role.USER, "question")
        val result = prependContextSummary(original, "old decisions")
        assertEquals("old decisions\n\nquestion", result.content)
        assertTrue(result.contentParts.isEmpty())
        val split = joinCompactSummaries(null, "segment A", "segment B")
        assertEquals("old decisions\n\nsegment A\n\nsegment B\n\nsegment C",
            joinCompactSummaries("old decisions", split, "segment C"))
    }

    @Test fun timeoutDoesNotRestartIdenticalLongRequest() {
        assertFalse(StreamRetryPolicy.canRetrySameProvider(RuntimeException(SocketTimeoutException()), 1_000))
        assertFalse(StreamRetryPolicy.canRetrySameProvider(IllegalStateException("no response from server (120s TTFB)"), 120_000))
        assertTrue(StreamRetryPolicy.canRetrySameProvider(java.io.IOException("reset"), 1_000))
        assertFalse(StreamRetryPolicy.canRetrySameProvider(java.io.IOException("reset"), 360_000))
        assertFalse(StreamRetryPolicy.canStartRecovery(360_000))
    }

    @Test fun reasoningIsCoalescedButFinalTailIsNeverLost() {
        val gate = ThinkingUpdateGate()
        assertTrue(gate.shouldPublish(0, 1))
        for (time in 1L..299L) assertFalse(gate.shouldPublish(time, time.toInt() + 1))
        assertTrue(gate.shouldPublish(300, 301))
        assertTrue(gate.shouldPublish(301, 302, force = true))
        assertFalse(gate.shouldPublish(302, 302, force = true))
        gate.reset()
        assertTrue(gate.shouldPublish(303, 1))
    }
}
