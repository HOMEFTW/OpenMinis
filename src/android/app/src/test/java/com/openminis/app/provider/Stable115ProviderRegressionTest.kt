package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.anthropic.AnthropicStream
import com.openminis.app.provider.deepseek.DeepSeekStream
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class Stable115ProviderRegressionTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))

    @Test fun `stable limited fallback spelling maps to the existing default strategy`() {
        assertEquals(FallbackStrategy.default, kotlinx.serialization.json.Json.decodeFromString(
            FallbackStrategy.serializer(), "\"limited\""))
    }

    @Test fun `Claude off uses each model's supported protocol and ignores relay version prefixes`() = runBlocking {
        val cases = listOf(
            "claude-sonnet-4-5" to null,
            "claude-opus-4-8" to "disabled",
            "claude-sonnet-5" to "disabled",
            "vendor-2/claude-sonnet-5-20260301" to "disabled",
            "claude-sonnet-5.5" to "between_tools",
            "claude-fable-5" to "adaptive",
            "claude-opus-5" to "adaptive",
        )
        MockWebServer().use { server ->
            for ((id, expected) in cases) {
                server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
                val provider = AnthropicProvider("key", LLMModel(id, id, "Anthropic", supportsReasoning = true),
                    basePath = server.url("/").toString().trimEnd('/'))
                provider.sendMessage(messages, null, 256, thinkingLevel = ThinkingLevel.OFF)
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(id, expected, body.optJSONObject("thinking")?.getString("type"))
                if (expected == "adaptive") assertEquals("low", body.getJSONObject("output_config").getString("effort"))
            }
        }
    }

    @Test fun `Gemini 3 and newer use levels with the supported off floor`() {
        fun config(id: String, level: ThinkingLevel = ThinkingLevel.OFF) =
            ThinkingRuleResolver.geminiThinkingConfig(id, level)!!
        assertEquals("minimal", config("google/gemini-3.6-flash").getString("thinkingLevel"))
        assertEquals("low", config("models/gemini-3-8-flash").getString("thinkingLevel"))
        assertEquals("low", config("GEMINI-4-argon").getString("thinkingLevel"))
        assertEquals("high", config("gemini-3-pro-preview", ThinkingLevel.MEDIUM).getString("thinkingLevel"))
        assertEquals("medium", config("gemini-4-argon", ThinkingLevel.MEDIUM).getString("thinkingLevel"))
        assertNull(ThinkingRuleResolver.geminiThinkingConfig("gemini-4-image-preview", ThinkingLevel.HIGH))
        assertNull(ThinkingRuleResolver.geminiThinkingConfig("gemini-2.5-flash-lite", ThinkingLevel.HIGH))
    }

    @Test fun `tool-only chat history always has a content field`() {
        val provider = OpenAIProvider("key", LLMModel.gpt4oMini, basePath = "https://relay.example/v1")
        val history = messages + listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call_1", "shell", JSONObject().put("command", "pwd")))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call_1", "shell", "ok"))),
        )
        val wire = provider.buildRequestBody(history, null, 1024, false, null, emptyList()).getJSONArray("messages")
        val assistant = (0 until wire.length()).map { wire.getJSONObject(it) }
            .single { it.optString("role") == "assistant" }
        assertTrue(assistant.has("content"))
        assertEquals("", assistant.getString("content"))
        assertEquals(1, assistant.getJSONArray("tool_calls").length())
    }

    @Test fun `Responses content filter ends as a refusal instead of an incomplete retry error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}\n\n"))
            val provider = OpenAIProvider("key", LLMModel.gpt4oMini,
                basePath = server.url("/v1").toString(), useResponsesAPI = true)
            val chunks = provider.streamMessage(messages, null, 256).toList()
            val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
            assertEquals("content_filter", finish.stopReason)
            assertTrue(StopReasons.isRefusal(finish.stopReason))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `native DeepSeek complete empty and reasoning-only replies reach loop recovery`() {
        for (thinking in listOf(false, true)) {
            val parser = DeepSeekStream("deepseek-v4.1-flash")
            parser.accept(JSONObject("""{"type":"message_start","message":{}}"""))
            if (thinking) {
                parser.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"plan","signature":"signed"}}"""))
                parser.accept(JSONObject("""{"type":"content_block_stop","index":0}"""))
            }
            parser.accept(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""))
            val chunks = parser.accept(JSONObject("""{"type":"message_stop"}"""))
            parser.endOfInput()
            assertEquals("end_turn", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
            assertTrue(chunks.none { it is LLMStreamChunk.ToolCallComplete })
            assertEquals(if (thinking) "plan" else "", chunks.filterIsInstance<LLMStreamChunk.ReasoningContent>().single().content)
        }
    }

    @Test fun `Anthropic refusal never releases tool calls`() {
        val parser = AnthropicStream("claude-fable-5")
        parser.accept(JSONObject("""{"type":"message_start"}"""))
        parser.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"shell","input":{}}}"""))
        parser.accept(JSONObject("""{"type":"content_block_stop","index":0}"""))
        parser.accept(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}"""))
        val chunks = parser.accept(JSONObject("""{"type":"message_stop"}"""))
        assertTrue(chunks.none { it is LLMStreamChunk.ToolCallComplete })
        val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
        assertTrue(finish.discardToolCalls)
        assertTrue(StopReasons.isRefusal(finish.stopReason))
    }

    @Test fun `DeepSeek reasoning markup retries are bounded and never duplicate real tools`() {
        for (marker in DsmlLeak.MARKERS) {
            assertTrue(DsmlLeak.isLeak(false, false, "stop", "argument $marker"))
            assertTrue(DsmlLeak.isLeak(false, false, "end_turn", "argument $marker"))
            assertFalse(DsmlLeak.isLeak(false, true, "tool_use", "argument $marker"))
            assertFalse(DsmlLeak.isLeak(true, false, "stop", "argument $marker"))
            assertFalse(DsmlLeak.isLeak(false, false, "max_tokens", "argument $marker"))
        }
        assertFalse(DsmlLeak.isLeak(false, false, "stop", "ordinary thinking"))
        assertEquals(DsmlLeak.Step.Retry(1), DsmlLeak.next(0))
        assertEquals(DsmlLeak.Step.Retry(2), DsmlLeak.next(1))
        assertSame(DsmlLeak.Step.GiveUp, DsmlLeak.next(2))
    }

    @Test fun `slow model response timeout only raises socket floor`() {
        assertEquals(600L, OpenAIProvider.socketReadTimeoutSeconds(null))
        assertEquals(600L, OpenAIProvider.socketReadTimeoutSeconds(30))
        assertEquals(600L, OpenAIProvider.socketReadTimeoutSeconds(300))
        assertEquals(630L, OpenAIProvider.socketReadTimeoutSeconds(600))
        assertEquals(3630L, OpenAIProvider.socketReadTimeoutSeconds(9999))
    }
}
