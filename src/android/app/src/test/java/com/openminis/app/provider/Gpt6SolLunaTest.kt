package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Gpt6SolLunaTest {
    private val ids = listOf("gpt-6-sol", "gpt-6-luna")
    private val levels = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX)
    private val efforts = listOf("none", "low", "medium", "high", "xhigh", "max")
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hello"))

    @Test fun staleNegativeCapabilityMetadataDoesNotHideKnownModelThinking() {
        for (id in ids + "deepseek-flash") {
            val stale = LLMModel(id, id, "Custom", supportsReasoning = false,
                reasoningEffortValues = emptyList(), declaresNoEffortTiers = true)
            val entry = ModelEntry("provider", stale)
            val resolved = entry.model
            val expected = if (id == "deepseek-flash") listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)
                else levels.drop(1)
            assertEquals(true, resolved.supportsReasoning)
            assertEquals(expected, resolved.chatThinkingLevels(entry.effectiveMaxThinkingLevel))
            assertEquals(expected.map { it.name.lowercase() }, resolved.reasoningEffortValues)
            assertEquals(false, resolved.declaresNoEffortTiers)
            // Repair the resolved capabilities without rewriting stored configuration.
            assertEquals(false, stale.supportsReasoning)
        }
    }

    @Test fun explicitUserDisableStillWinsOverKnownLunaCapabilities() {
        val base = LLMModel("gpt-6-luna", "GPT-6 Luna", "Custom", supportsReasoning = false)
        val disabled = ModelEntry("provider", base, ModelOverrides(supportsReasoning = false))
        assertEquals(false, disabled.model.supportsReasoning)
        assertEquals(ThinkingLevel.OFF, disabled.effectiveMaxThinkingLevel)
        val capped = ModelEntry("provider", base, ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH))
        assertEquals(levels.drop(1).take(3), capped.model.chatThinkingLevels(capped.effectiveMaxThinkingLevel))
        val unrelated = ModelEntry("provider", LLMModel("custom-model", "Custom", "Custom", supportsReasoning = false))
        assertEquals(false, unrelated.model.supportsReasoning)
    }

    @Test fun builtinsDiscoveryAndSavedEntriesExposeFiveEffortsAndVision() = runBlocking {
        val oauth = OpenAIModelsApi.fetchModelsOAuth()
        val server = MockWebServer()
        server.start()
        try {
            for (id in ids) {
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"$id"}]}"""))
                val discovered = OpenAIModelsApi.fetchModels("test", server.url("/").toString().trimEnd('/')).single()
                val persisted = ModelEntry("provider", LLMModel(id, id, "Custom", supportsReasoning = null,
                    reasoningEffortValues = null, inputModalities = null, contextWindow = null, maxOutputTokens = null)).model
                for (model in listOf(LLMModel.allOpenAI.first { it.id == id }, oauth.first { it.id == id },
                    discovered, persisted, LLMModel("openai/$id-2026-09-24", id, "Custom"))) {
                    assertEquals(true, model.supportsReasoning)
                    assertEquals(levels.drop(1), model.selectableThinkingLevels)
                    assertEquals(levels.drop(1), model.chatThinkingLevels(model.catalogMaxThinkingLevel))
                    assertEquals(1_050_000, model.contextWindowTokens)
                    assertEquals(128_000, model.maxOutputTokens)
                    assertEquals(listOf("text", "image"), model.inputModalities)
                }
            }
            assertNull(LLMModel("gpt-6-sol-fake", "fake", "Custom").supportsReasoning)
            assertNull(LLMModel("gpt-6-luna-fake", "fake", "Custom").reasoningEffortValues)
        } finally { server.shutdown() }
    }

    @Test fun allEffortsReachBothRequestFormatsAndOffIsNoneEvenWithOAuth() {
        for (id in ids) {
            val model = LLMModel(id, id, "OpenAI")
            for (provider in listOf(OpenAIProvider("test", model), OpenAIProvider(oauthTokenProvider = { "test" }, model = model))) {
                for ((level, effort) in (levels + ThinkingLevel.ULTRA).zip(efforts + "max")) {
                    val chat = provider.buildRequestBody(messages, null, 1024, true, 0.7, emptyList(), thinkingLevel = level)
                    assertEquals(effort, chat.getString("reasoning_effort"))
                    assertEquals(level == ThinkingLevel.OFF, chat.has("temperature"))
                    val responses = provider.buildResponsesAPIBody(messages, null, 1024, true, emptyList(), thinkingLevel = level)
                    assertEquals(effort, responses.getJSONObject("reasoning").getString("effort"))
                }
            }
        }
    }

    @Test fun reasoningRequestsAutomaticallyUseResponses() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            for (id in ids) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
                ))
                val provider = OpenAIProvider("test", LLMModel(id, id, "Custom"), server.url("/").toString().trimEnd('/'))
                assertEquals("ok", provider.sendMessage(messages, null, 1024, thinkingLevel = ThinkingLevel.MAX).text)
                val request = server.takeRequest()
                assertEquals("/responses", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(id, body.getString("model"))
                assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
            }
        } finally { server.shutdown() }
    }
}
