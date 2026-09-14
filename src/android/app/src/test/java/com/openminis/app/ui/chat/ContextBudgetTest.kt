package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun `effective window uses the smallest positive limit`() {
        assertEquals(
            80_000,
            ChatViewModel.effectiveContextWindowTokensFor(
                modelWindow = 400_000,
                configuredWindow = 105_000,
                groupLimit = 80_000,
            ),
        )
        assertEquals(
            105_000,
            ChatViewModel.effectiveContextWindowTokensFor(
                modelWindow = null,
                configuredWindow = 105_000,
                groupLimit = 0,
            ),
        )
    }

    @Test
    fun `output budget never exceeds current remaining context`() {
        assertEquals(70, ChatViewModel.maxOutputTokensFor(100, 80, 30))
        assertEquals(80, ChatViewModel.maxOutputTokensFor(100, 80, 0))
        assertEquals(0, ChatViewModel.maxOutputTokensFor(100, 80, 100))
        assertEquals(0, ChatViewModel.maxOutputTokensFor(100, 80, 101))
    }

    @Test
    fun `displayed max output uses effective window and model ceiling`() {
        assertEquals(80_000, ChatViewModel.displayedMaxOutputTokensFor(80_000, 128_000))
        assertEquals(64_000, ChatViewModel.displayedMaxOutputTokensFor(1_000_000, 64_000))
        assertEquals(null, ChatViewModel.displayedMaxOutputTokensFor(null, null))
    }

    @Test
    fun `request estimate includes prompt tools and structured history`() {
        val tool = AgentToolDefinition(
            name = "lookup",
            description = "Look up a value",
            parameters = emptyMap(),
        )
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "ignored when structured parts are present",
                contentParts = listOf(
                    AgentContentPart.Text("current question"),
                    AgentContentPart.ToolResult(
                        id = "call-1",
                        name = "lookup",
                        content = "tool result",
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "call-1",
                        name = "lookup",
                        input = JSONObject("{\"key\":\"value\"}"),
                    ),
                ),
            ),
        )

        val withoutExtras = ChatViewModel.estimateRequestPayload(messages, null, emptyList())
        val withExtras = ChatViewModel.estimateRequestPayload(
            messages,
            systemPrompt = "Follow the safety policy.",
            tools = listOf(tool),
        )

        assertTrue(withExtras.inputTokens > withoutExtras.inputTokens)
        assertEquals(0, withExtras.imageTokens)
        assertEquals(0L, withExtras.imageBytes)
    }

    @Test
    fun `request estimate counts image tokens separately from image bytes`() {
        val image = ByteArray(7)
        val estimate = ChatViewModel.estimateRequestPayload(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(AgentContentPart.ImageData(image, "image/jpeg")),
                ),
            ),
            systemPrompt = null,
            tools = emptyList(),
        )

        assertEquals(7L, estimate.imageBytes)
        assertEquals(1_000, estimate.imageTokens)
        assertTrue(estimate.inputTokens >= estimate.imageTokens)
    }
}
