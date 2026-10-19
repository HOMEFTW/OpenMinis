package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelLimits
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAIModelLimitsTest {
    @Test fun acceptsProviderLimitShapesAndIgnoresInvalidLimits() {
        assertEquals(OpenAIModelLimits(8192, 1024), OpenAIModelLimits.read(JSONObject(
            """{"context_length":8192,"max_output_tokens":1024,"limit":{"context":99999,"output":9999}}""")))
        assertEquals(OpenAIModelLimits(32768, 4096), OpenAIModelLimits.read(JSONObject(
            """{"limit":{"context":32768},"top_provider":{"max_completion_tokens":4096}}""")))
        assertEquals(OpenAIModelLimits(16384, 2048), OpenAIModelLimits.read(JSONObject(
            """{"max_model_len":"16384","max_completion_tokens":"2048"}""")))
        assertEquals(OpenAIModelLimits(null, null), OpenAIModelLimits.read(JSONObject(
            """{"context_length":0,"max_output_tokens":-1,"max_model_len":999999999999}""")))
    }

    private fun <T> withRegistry(block: () -> T): T {
        val registry = ModelsDevApi::class.java.getDeclaredField("cachedRegistry").apply { isAccessible = true }
        val timestamp = ModelsDevApi::class.java.getDeclaredField("cacheTimestamp").apply { isAccessible = true }
        val oldRegistry = registry.get(ModelsDevApi)
        val oldTimestamp = timestamp.getLong(ModelsDevApi)
        val model = ModelsDevApi.ModelDevEntry("gpt-6.1-sol", null, null, 1_050_000, 128_000, true, null,
            listOf("text", "image"), listOf("text"), listOf("low", "high"), releaseDate = null, outputCost = null)
        registry.set(ModelsDevApi, mapOf("openai" to ModelsDevApi.ProviderEntry("openai", "OpenAI", null, mapOf(model.id to model))))
        timestamp.setLong(ModelsDevApi, System.currentTimeMillis())
        try { return block() } finally { registry.set(ModelsDevApi, oldRegistry); timestamp.setLong(ModelsDevApi, oldTimestamp) }
    }

    @Test fun customModelsEndpointLimitsSurviveBothEnrichmentPasses() = withRegistry {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-6.1-sol","context_length":8192,"max_output_tokens":1024,"service_tiers":[{"id":"ultrafast"}]}]}"""))
            val models = runBlocking { OpenAIModelsApi.fetchModels("test", server.url("/v1").toString()) }
            val second = ModelsDevApi.enrichModels(models, preserveProviderLimits = true).single()
            assertEquals(8192, second.contextWindow)
            assertEquals(1024, second.maxOutputTokens)
            assertEquals(listOf("text", "image"), second.inputModalities)
            assertEquals(true, second.supportsReasoning)
            assertEquals(listOf("ultrafast"), second.supportedServiceTiers)
        }
    }

    @Test fun missingLimitsStillUseCatalogAndOfficialCatalogBehaviorIsRetained() = withRegistry {
        val model = LLMModel("gpt-6.1-sol", "Sol", "Custom", contextWindow = null, maxOutputTokens = null)
        assertEquals(1_050_000, ModelsDevApi.enrichModels(listOf(model), true).single().contextWindow)
        val official = model.copy(provider = "OpenAI", contextWindow = 8192, maxOutputTokens = 1024)
        assertEquals(1_050_000, ModelsDevApi.enrichModels(listOf(official)).single().contextWindow)
    }

    @Test fun officialHostDetectionDoesNotTrustLookalikeUrls() {
        assertTrue(OpenAIModelsApi.isOfficialOpenAI("https://api.openai.com/v1"))
        assertFalse(OpenAIModelsApi.isOfficialOpenAI("https://api.openai.com.relay.example/v1"))
        assertFalse(OpenAIModelsApi.isOfficialOpenAI("https://relay.example/api.openai.com/v1"))
    }
}
