package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.anthropic.AnthropicStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicRefusalDetailsTest {
    @Test fun `policy details survive usage-only deltas until message stop`() {
        val stream = AnthropicStream("claude-fable-5")
        stream.accept(JSONObject("""{"type":"message_start"}"""))
        stream.accept(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"category":"policy","explanation":"Provider explanation"}}}"""))
        stream.accept(JSONObject("""{"type":"message_delta","delta":{},"usage":{"output_tokens":0}}"""))
        val finish = stream.accept(JSONObject("""{"type":"message_stop"}"""))
            .filterIsInstance<LLMStreamChunk.Finished>().single()
        assertEquals("refusal", finish.stopReason)
        assertTrue(finish.discardToolCalls)
        assertEquals("policy", finish.refusalCategory)
        assertEquals("Provider explanation", finish.refusalExplanation)
        assertEquals("Blocked (policy)\n\nProvider explanation", StopReasons.refusalErrorText(
            "Blocked (${finish.refusalCategory})", "Declined", finish.refusalExplanation))
    }

    @Test fun `absent and null policy details do not display a literal null`() {
        for (details in listOf("null", "{}", """{"category":null,"explanation":null}""")) {
            val stream = AnthropicStream("claude-fable-5")
            stream.accept(JSONObject("""{"type":"message_start"}"""))
            stream.accept(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":$details}}"""))
            val finish = stream.accept(JSONObject("""{"type":"message_stop"}"""))
                .filterIsInstance<LLMStreamChunk.Finished>().single()
            assertNull(finish.refusalCategory)
            assertNull(finish.refusalExplanation)
            assertEquals("Declined", StopReasons.refusalErrorText(null, "Declined", finish.refusalExplanation))
        }
    }
}
