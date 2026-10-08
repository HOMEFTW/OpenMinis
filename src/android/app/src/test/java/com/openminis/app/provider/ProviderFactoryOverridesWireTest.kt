package com.openminis.app.provider

import com.openminis.app.data.model.*
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderFactoryOverridesWireTest {
    @Test fun factoryPropagatesSessionAndOverridesToTheRealRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"))
            val instance = ProviderInstance(id = "relay", label = "Relay", providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/").toString(), appendV1Suffix = false)
            val overrides = ModelOverrides(temperature = 0.0, topP = 0.9, customHeaders = mapOf("X-Tuning" to "enabled"),
                extraBodyParams = JsonObject(mapOf("model" to JsonPrimitive("wrong"), "vendor_flag" to JsonPrimitive(true))))
            val provider = ProviderFactory.create(instance, "key", LLMModel.gpt4oMini,
                sessionId = "conversation-1", overrides = overrides) as OpenAIProvider
            assertEquals("conversation-1", provider.sessionId)
            assertEquals(overrides, provider.modelOverrides)
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertEquals("enabled", request.getHeader("X-Tuning"))
            assertNull(request.getHeader(OpenCodeSessionHeader.HEADER))
            assertEquals(LLMModel.gpt4oMini.id, body.getString("model"))
            assertEquals(0.0, body.getDouble("temperature"), 0.0)
            assertEquals(0.9, body.getDouble("top_p"), 0.0)
            assertTrue(body.getBoolean("vendor_flag"))
        } finally { server.shutdown() }
    }
}
