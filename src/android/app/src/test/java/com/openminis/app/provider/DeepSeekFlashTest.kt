package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekFlashTest {
    private val ids = listOf("deepseek-flash", "deepseek-v4.1-flash", "deepseek/deepseek-flash")
    private val levels = listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)
    private val efforts = listOf("low", "high", "max")
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "Hello"))

    @Test fun capabilitiesExposeDistinctEffortsWithoutACatalogRefresh() {
        for (id in ids) {
            val model = LLMModel(id, id, "Custom")
            assertEquals(true, model.supportsReasoning)
            assertEquals(levels, model.selectableThinkingLevels)
            assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
            assertEquals(1_000_000, model.contextWindowTokens)
            assertEquals(384_000, model.maxOutputTokens)
            assertEquals(listOf("text", "image"), model.inputModalities)
        }
        for (id in listOf("deepseek-chat", "deepseek-reasoner", "deepseek-flash-fake", "deepseek-v4-pro")) {
            assertNull(LLMModel(id, id, "Custom").reasoningEffortValues)
        }
    }

    @Test fun persistedUnknownCapabilitiesGainDefaultsButUserOverridesWin() {
        val base = LLMModel("deepseek-flash", "Flash", "Custom", supportsReasoning = null,
            reasoningEffortValues = null, inputModalities = null, contextWindow = null, maxOutputTokens = null)
        val entry = ModelEntry("provider", base)
        assertEquals(true, entry.model.supportsReasoning)
        assertEquals(levels, entry.model.selectableThinkingLevels)
        assertEquals(1_000_000, entry.model.contextWindowTokens)
        val overridden = entry.copy(overrides = ModelOverrides(supportsReasoning = false, contextWindow = 64_000))
        assertEquals(false, overridden.model.supportsReasoning)
        assertEquals(64_000, overridden.model.contextWindowTokens)
    }

    @Test fun discoveredOfficialModelHasReasoningAndVision() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"deepseek-flash"}]}"""))
            val model = OpenAIModelsApi.fetchModels("test", server.url("/").toString().trimEnd('/')).single()
            assertEquals(true, model.supportsReasoning)
            assertEquals(levels, model.selectableThinkingLevels)
            assertEquals(listOf("text", "image"), model.inputModalities)
        } finally { server.shutdown() }
    }

    @Test fun chatEffortsAndExplicitOffReachTheRequest() {
        for (id in ids) {
            val provider = OpenAIProvider("test", LLMModel(id, id, "Custom"), "https://api.deepseek.com")
            for ((level, effort) in (levels + ThinkingLevel.MEDIUM + ThinkingLevel.XHIGH + ThinkingLevel.ULTRA)
                .zip(efforts + "high" + "high" + "max")) {
                val body = provider.buildRequestBody(messages, null, 1024, true, 0.7, emptyList(), thinkingLevel = level)
                assertEquals(effort, body.getString("reasoning_effort"))
                assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
                assertFalse(body.getJSONObject("thinking").has("reasoning_effort"))
            }
            val off = provider.buildRequestBody(messages, null, 1024, true, null, emptyList(), thinkingLevel = ThinkingLevel.OFF)
            assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
            assertFalse(off.has("reasoning_effort"))
        }
    }

    @Test fun responsesEffortsAndExplicitOffReachTheRequest() {
        val provider = OpenAIProvider("test", LLMModel("deepseek-flash", "Flash", "Custom"), "https://api.deepseek.com")
        for ((level, effort) in (levels + ThinkingLevel.OFF + ThinkingLevel.MEDIUM).zip(efforts + "none" + "high")) {
            val body = provider.buildResponsesAPIBody(messages, null, 1024, true, emptyList(), thinkingLevel = level)
            assertEquals(effort, body.getJSONObject("reasoning").getString("effort"))
            assertFalse(body.has("thinking"))
        }
    }

    @Test fun pickerShowsOnlyDistinctLevelsAndHonorsCeilings() {
        val model = LLMModel("deepseek-flash", "Flash", "Custom")
        assertEquals(levels, model.chatThinkingLevels(ThinkingLevel.MAX))
        assertEquals(levels.take(2), model.chatThinkingLevels(ThinkingLevel.HIGH))
        assertEquals(emptyList<ThinkingLevel>(), model.chatThinkingLevels(ThinkingLevel.OFF))
        assertEquals(ThinkingLevel.HIGH, LLMModel.deepSeekFlashThinkingLevel(ThinkingLevel.MEDIUM))
        assertEquals(ThinkingLevel.OFF, LLMModel.deepSeekFlashThinkingLevel(ThinkingLevel.OFF))
    }

    @Test fun gatewayRulesKeepPriorityOverNativeThinking() {
        val model = LLMModel("deepseek-flash", "Flash", "Custom")
        for (base in listOf("https://openrouter.ai/api/v1", "https://ark.cn-beijing.volces.com/api/v3")) {
            val body = OpenAIProvider("test", model, base)
                .buildRequestBody(messages, null, 1024, true, null, emptyList(), thinkingLevel = ThinkingLevel.MAX)
            assertFalse(body.has("thinking"))
            if (base.contains("openrouter")) assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
            else assertEquals("max", body.getString("reasoning_effort"))
        }
    }

    @Test fun previousAssistantReasoningIsPreservedEvenWithoutToolCallsInThatTurn() {
        val provider = OpenAIProvider("test", LLMModel("deepseek-flash", "Flash", "Custom"))
        val history = messages + LLMMessage(LLMMessage.Role.ASSISTANT, "Answer", reasoningContent = "Saved reasoning") + messages
        val body = provider.buildRequestBody(history, null, 1024, true, null, emptyList(), thinkingLevel = ThinkingLevel.HIGH)
        assertEquals("Saved reasoning", body.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
    }

    @Test fun anthropicEffortsUseOutputConfigInsteadOfTokenBudgets() {
        val provider = AnthropicProvider("test", LLMModel("deepseek-flash", "Flash", "Custom"), "https://api.deepseek.com/anthropic")
        val builder = AnthropicProvider::class.java.declaredMethods.single { it.name == "buildRequestBody" }
            .apply { isAccessible = true }
        fun body(level: ThinkingLevel) = builder.invoke(provider, messages, null, 1024, true, null,
            emptyList<LLMMessage.ImagePart>(), emptyList<Any>(), level) as JSONObject
        for ((level, effort) in levels.zip(efforts)) {
            val body = body(level)
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
            assertEquals(effort, body.getJSONObject("output_config").getString("effort"))
            assertFalse(body.getJSONObject("thinking").has("budget_tokens"))
        }
        val off = body(ThinkingLevel.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse(off.has("output_config"))
    }
}
