package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.anthropic.AnthropicReplay
import com.openminis.app.provider.anthropic.AnthropicStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicReplayTest {
    private fun blocks() = JSONArray("""[
        {"type":"thinking","thinking":"first","signature":"sig-one"},
        {"type":"text","text":"before"},
        {"type":"tool_use","id":"a","name":"read_file","input":{"path":"fixture"}},
        {"type":"redacted_thinking","data":"opaque-redacted"},
        {"type":"thinking","thinking":"second","signature":"sig-two"},
        {"type":"text","text":"after"}
    ]""")
    private fun message() = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
        AgentContentPart.Text("before"), AgentContentPart.ToolUse("renamed_a", "read_file", JSONObject("""{"path":"fixture"}""")),
        AgentContentPart.Text("after")), reasoningContent = "firstsecond", anthropicReplay = AnthropicReplay.capture("claude-fixture", blocks()))

    @Test fun `signed and redacted blocks replay exactly in original order with renamed tools`() {
        val replay = AnthropicReplay.read(message(), "claude-fixture")!!
        assertEquals(listOf("thinking", "text", "tool_use", "redacted_thinking", "thinking", "text"),
            (0 until replay.length()).map { replay.getJSONObject(it).getString("type") })
        assertEquals("sig-one", replay.getJSONObject(0).getString("signature"))
        assertEquals("opaque-redacted", replay.getJSONObject(3).getString("data"))
        assertEquals("sig-two", replay.getJSONObject(4).getString("signature"))
        assertEquals("renamed_a", replay.getJSONObject(2).getString("id"))
        assertEquals("first", replay.getJSONObject(0).getString("thinking"))
        assertEquals("second", replay.getJSONObject(4).getString("thinking"))
    }
    @Test fun `altered reasoning and different models never reuse an opaque signature`() {
        assertNull(AnthropicReplay.read(message().copy(reasoningContent = "wrongsecond"), "claude-fixture"))
        assertNull(AnthropicReplay.read(message(), "another-model"))
    }
    @Test fun `official endpoint sends captured signatures and redacted blocks`() {
        val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45.copy(id = "claude-fixture"))
        val method = provider.javaClass.getDeclaredMethod("buildMessages", List::class.java, List::class.java).apply { isAccessible = true }
        val history = listOf(message(), LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("renamed_a", "read_file", "ok"))))
        val encoded = method.invoke(provider, history, emptyList<LLMMessage.ImagePart>()) as JSONArray
        val content = encoded.getJSONObject(0).getJSONArray("content")
        assertEquals("sig-one", content.getJSONObject(0).getString("signature"))
        assertEquals("opaque-redacted", content.getJSONObject(3).getString("data"))
    }
    @Test fun `signed and redacted-only assistant messages are not stripped as empty`() {
        val provider = AnthropicProvider("fixture", LLMModel.claudeHaiku45.copy(id = "claude-fixture"))
        val method = provider.javaClass.getDeclaredMethod("buildMessages", List::class.java, List::class.java).apply { isAccessible = true }
        listOf(
            JSONArray("""[{"type":"redacted_thinking","data":"opaque"}]"""),
            JSONArray("""[{"type":"thinking","thinking":"","signature":"signed-empty"}]""")
        ).forEach { blocks ->
            val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "", reasoningContent = "",
                anthropicReplay = AnthropicReplay.capture("claude-fixture", blocks)), LLMMessage(LLMMessage.Role.USER, "next"))
            val encoded = method.invoke(provider, history, emptyList<LLMMessage.ImagePart>()) as JSONArray
            assertEquals(2, encoded.length())
            assertEquals("assistant", encoded.getJSONObject(0).getString("role"))
            assertEquals(blocks.toString(), encoded.getJSONObject(0).getJSONArray("content").toString())
        }
    }
    @Test fun `stream accumulates signature deltas and preserves redacted-only reasoning`() {
        val stream = AnthropicStream("claude-fixture")
        val chunks = mutableListOf<LLMStreamChunk>()
        fun accept(raw: String) { chunks.addAll(stream.accept(JSONObject(raw))) }
        accept("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""")
        accept("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thought"}}""")
        accept("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"part-"}}""")
        accept("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"two"}}""")
        accept("""{"type":"content_block_stop","index":0}""")
        accept("""{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}""")
        accept("""{"type":"content_block_stop","index":1}""")
        accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        assertTrue(chunks.none { it is LLMStreamChunk.ReasoningContent || it is LLMStreamChunk.Finished })
        accept("""{"type":"message_stop"}""")
        val captured = chunks.filterIsInstance<LLMStreamChunk.ReasoningContent>().single()
        val message = LLMMessage(LLMMessage.Role.ASSISTANT, "", reasoningContent = captured.content, anthropicReplay = captured.anthropicReplay)
        val replay = AnthropicReplay.read(message, "claude-fixture")!!
        assertEquals("part-two", replay.getJSONObject(0).getString("signature"))
        assertEquals("opaque", replay.getJSONObject(1).getString("data"))
    }
}
