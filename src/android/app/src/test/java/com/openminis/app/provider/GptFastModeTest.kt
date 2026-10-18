package com.openminis.app.provider

import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GptFastModeTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    @After fun resetFastMode() = FastModePrefs.setCachedEnabledForTest(false)

    private fun provider(id: String, type: ProviderType = ProviderType.openAI): OpenAIProvider =
        ProviderFactory.create(
            ProviderInstance("test", "Test", type, ProviderCredential.apiKey),
            "test-key", LLMModel(id, id, "Test"),
        ) as OpenAIProvider

    private fun body(provider: OpenAIProvider, responses: Boolean, level: ThinkingLevel = ThinkingLevel.HIGH): JSONObject =
        if (responses) provider.buildResponsesAPIBody(messages, null, 1024, false, thinkingLevel = level)
        else provider.buildRequestBody(messages, null, 1024, false, null, imageParts = emptyList(), thinkingLevel = level)

    @Test fun chatCompletionsRequestsFastForGptFamilies() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-6-astra", "gpt-5.6-sol", "gpt-4.1")) {
            assertEquals(id, "priority", body(provider(id), responses = false).optString("service_tier"))
        }
    }

    @Test fun responsesSupportsApiKeysDedicatedTypeAndCodexOAuth() {
        FastModePrefs.setCachedEnabledForTest(true)
        val providers = listOf(
            provider("gpt-6.1-sol"),
            provider("gpt-6.1-sol", ProviderType.openAIResponses),
            OpenAIProvider(oauthTokenProvider = { "test-token" }, model = LLMModel.gpt61Sol),
        )
        providers.forEach { assertEquals("priority", body(it, responses = true).getString("service_tier")) }
    }

    @Test fun rawTextOutputAliasesKeepFastInBothRequestFormats() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (output in listOf("text_output", "TEXT_OUTPUT")) {
            val provider = OpenAIProvider(apiKey = "test-key",
                model = LLMModel.gpt61Sol.copy(outputModalities = listOf(output)))
            for (responses in listOf(false, true)) {
                assertEquals(output, "priority", body(provider, responses).optString("service_tier"))
            }
        }
    }

    @Test fun togglingFastAppliesToTheNextRequestOnTheSameProvider() {
        for (responses in listOf(false, true)) {
            val provider = provider("gpt-6-luna")
            FastModePrefs.setCachedEnabledForTest(false)
            assertFalse(body(provider, responses).has("service_tier"))
            FastModePrefs.setCachedEnabledForTest(true)
            assertEquals("priority", body(provider, responses).optString("service_tier"))
            FastModePrefs.setCachedEnabledForTest(false)
            assertFalse(body(provider, responses).has("service_tier"))
        }
    }

    @Test fun fastDoesNotChangeTheSelectedReasoningEffort() {
        for (responses in listOf(false, true)) {
            val provider = provider("gpt-6-sol")
            for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
                fun effort(value: JSONObject) = if (responses) value.getJSONObject("reasoning").getString("effort")
                    else value.getString("reasoning_effort")
                FastModePrefs.setCachedEnabledForTest(false)
                val standard = effort(body(provider, responses, level))
                FastModePrefs.setCachedEnabledForTest(true)
                val fast = body(provider, responses, level)
                assertEquals(standard, effort(fast))
                assertEquals("priority", fast.optString("service_tier"))
            }
        }
    }

    @Test fun gptNamesDoNotEnableFastOnUnrelatedProviderRoutes() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (type in listOf(ProviderType.openRouter, ProviderType.kimiCode)) {
            val provider = provider("gpt-6-luna", type)
            for (responses in listOf(false, true)) {
                assertFalse("$type / responses=$responses", body(provider, responses).has("service_tier"))
            }
        }
    }

    @Test fun imageModelsOtherFamiliesAndLookalikesDoNotReceiveFastTier() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("gpt-image-2.5-sunburst", "deepseek-flash", "claude-sonnet-5", "not-gpt-6-luna")) {
            for (responses in listOf(false, true)) {
                assertFalse(id, body(provider(id), responses).has("service_tier"))
            }
        }
    }

    @Test fun prefixedModelsAndSnapshotsUseTheSameFastSetting() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("openai/gpt-6-luna", "gpt-6-sol-2026-10-01", "OPENAI/GPT-6.1-SOL")) {
            for (responses in listOf(false, true)) {
                assertEquals(id, "priority", body(provider(id), responses).optString("service_tier"))
            }
        }
    }
}
