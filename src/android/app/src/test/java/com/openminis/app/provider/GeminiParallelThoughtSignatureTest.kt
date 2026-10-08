package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiParallelThoughtSignatureTest {
    @Test fun `mixed signature batches are never partially replayed on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))
            val provider = GeminiProvider(apiKey = "test",
                model = LLMModel("gemini-3-flash-preview", "Gemini 3", "Google Gemini"),
                basePath = server.url("/").toString().trimEnd('/'))
            val history = listOf(
                LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                    AgentContentPart.ToolUse("a", "shell_execute", JSONObject(), thoughtSignature = "sig"),
                    AgentContentPart.ToolUse("b", "shell_execute", JSONObject()))),
                LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                    AgentContentPart.ToolResult("a", "shell_execute", "one"),
                    AgentContentPart.ToolResult("b", "shell_execute", "two"))))
            runBlocking { provider.sendMessage(history, "system", 512) }
            val wire = server.takeRequest().body.readUtf8()
            assertFalse(wire.contains("functionCall"))
            assertFalse(wire.contains("functionResponse"))
            assertTrue(wire.contains("one"))
            assertTrue(wire.contains("two"))
        } finally { server.shutdown() }
    }
}
