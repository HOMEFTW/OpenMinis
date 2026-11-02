package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class Stable115ModelsApiTest {
    @Test fun `endpoint limits survive serialization and later catalog enrichment`() {
        val model = com.openminis.app.data.model.LLMModel("custom", "Custom", "OpenAI",
            contextWindow = 8192, maxOutputTokens = 1024, limitsReportedByEndpoint = true)
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(com.openminis.app.data.model.LLMModel.serializer(),
            json.encodeToString(com.openminis.app.data.model.LLMModel.serializer(), model))
        val dev = ModelsDevApi.ModelDevEntry(id = "custom", name = null, family = null, contextWindow = 1000000, maxOutputTokens = 64000, reasoning = null, interleavedField = null, inputModalities = null, outputModalities = null, reasoningEffortValues = null, releaseDate = null, outputCost = null)
        val apply = ModelsDevApi::class.java.getDeclaredMethod("applyDevData",
            com.openminis.app.data.model.LLMModel::class.java, ModelsDevApi.ModelDevEntry::class.java).apply { isAccessible = true }
        val enriched = apply.invoke(ModelsDevApi, restored, dev) as com.openminis.app.data.model.LLMModel
        assertEquals(true, enriched.limitsReportedByEndpoint)
        assertEquals(8192, enriched.contextWindow)
        assertEquals(1024, enriched.maxOutputTokens)
    }

    @Test fun `relay model list envelopes and id spellings are accepted`() = runBlocking {
        val entry = """{"model_id":"private-model","contextWindow":8192,"maxOutputTokens":1024}"""
        val bodies = listOf("[$entry]", """{"models":[$entry]}""", """{"items":[$entry]}""",
            """{"results":[$entry]}""", """{"data":{"models":[$entry]}}""", """{"data":["private-model"]}""")
        MockWebServer().use { server ->
            for (body in bodies) {
                server.enqueue(MockResponse().setBody(body))
                val result = OpenAIModelsApi.fetchModelsDetailed("", server.url("/v1").toString())
                assertNull(result.error)
                val model = result.models.single()
                assertEquals("private-model", model.id)
                if ("contextWindow" in body) {
                    assertEquals(true, model.limitsReportedByEndpoint)
                    assertEquals(8192, model.contextWindow)
                    assertEquals(1024, model.maxOutputTokens)
                }
            }
        }
    }

    @Test fun `failed and invalid relay lists return a reason without built-in GPT fallback`() = runBlocking {
        val responses = listOf(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""),
            MockResponse().setBody("<html>gateway error</html>"),
            MockResponse().setBody("""{"data":[]}"""),
        )
        MockWebServer().use { server ->
            for (response in responses) {
                server.enqueue(response)
                val result = OpenAIModelsApi.fetchModelsDetailed("fixture", server.url("/v1").toString())
                assertTrue(result.models.isEmpty())
                assertFalse(result.error.isNullOrBlank())
            }
        }
    }

    @Test fun `Anthropic relay also reads endpoint context and output limits`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"private-model","contextWindow":200000,"maxOutputTokens":16000}]}"""))
            val model = AnthropicModelsApi.fetchModels("fixture", server.url("/v1").toString()).single()
            assertEquals(200000, model.contextWindow)
            assertEquals(16000, model.maxOutputTokens)
        }
    }
}
