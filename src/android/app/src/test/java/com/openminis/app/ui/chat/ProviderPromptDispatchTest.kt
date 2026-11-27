package com.openminis.app.ui.chat

import com.openminis.app.BuildConfig
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class ProviderPromptDispatchTest(private val type: ProviderType, private val modelId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun routes(): List<Array<Any>> = ProviderSystemPromptTest.routes()
            .filter { it[0] != ProviderType.openRouter }
    }

    @Test fun `shared prompt gate reaches provider HTTP request and returns a reply`() = runBlocking {
        MockWebServer().use { server ->
            val response = when (type) {
                ProviderType.anthropic -> """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                ProviderType.gemini -> """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}]}"""
                ProviderType.deepSeek -> listOf(
                    """{"type":"message_start","message":{"usage":{"input_tokens":1}}}""",
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                    """{"type":"message_stop"}""",
                ).joinToString("") { "data: $it\n\n" }
                // Both GPT-6 fixtures use Responses, including the OpenAI entry.
                ProviderType.openAI, ProviderType.openAIResponses ->
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                else -> "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            }
            server.enqueue(MockResponse().setBody(response).setHeader("Content-Type",
                if (type in setOf(ProviderType.anthropic, ProviderType.gemini)) "application/json"
                else "text/event-stream"))
            val provider = ProviderFactory.create(
                ProviderInstance(id = "dispatch-test", label = "Dispatch", providerType = type,
                    credentialType = ProviderCredential.apiKey,
                    customBaseURL = server.url("/").toString(), appendV1Suffix = false),
                "synthetic-key", LLMModel(modelId, modelId, type.name),
            )
            val prompt = systemPromptFor(provider, "Answer the user concisely.")
            val reply = withTimeout(10_000) {
                provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hello")), prompt, 128)
            }
            assertEquals("ok", reply.text)
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertTrue(request.body.readUtf8().contains("Answer the user concisely."))
            assertEquals(1, server.requestCount)
        }
    }
}

class ClaudeOAuthSystemPromptTest {
    @Test fun `Claude OAuth still requires its own customization`() {
        val provider = AnthropicProvider("synthetic-key", isOAuth = true)
        val prefix = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (prefix.isEmpty()) {
            val error = assertThrows(IllegalStateException::class.java) {
                systemPromptFor(provider, "Answer the user.")
            }
            assertTrue(error.message.orEmpty().contains("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT"))
        } else {
            assertEquals("$prefix\n\nAnswer the user.", systemPromptFor(provider, "Answer the user."))
            assertEquals("$prefix\n\nAnswer the user.", systemPromptFor(provider, "$prefix\n\nAnswer the user."))
        }
    }
}
