package com.openminis.app.provider

import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test

/** The shared GPT speed preference must never request a service tier for Grok. */
class XAIPriorityProcessingTest {
    private val model = LLMModel("grok-4", "Grok 4", "xai")
    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    @After fun tearDown() = FastModePrefs.setCachedEnabledForTest(false)

    private fun body(provider: OpenAIProvider, responses: Boolean): JSONObject =
        if (responses) provider.buildResponsesAPIBody(history, null, 1024, false)
        else provider.buildRequestBody(history, null, 1024, false, null, imageParts = emptyList())

    private fun factoryProvider(type: ProviderType, model: LLMModel = this.model) = ProviderFactory.create(
        ProviderInstance("grok-fast-test", "Grok", type, ProviderCredential.apiKey), "test-key", model,
    ) as OpenAIProvider

    private fun grokProviders() = listOf(
        factoryProvider(ProviderType.xAI),
        factoryProvider(ProviderType.openAI),
        factoryProvider(ProviderType.openAIResponses),
        OpenAIProvider(apiKey = "test-key", model = model, basePath = "https://api.x.ai/v1"),
        OpenAIProvider.oauthOpenAICompat({ "test-token" }, model, "https://api.x.ai/v1"),
    )

    @Test fun apiKeyOAuthAndCompatibleGrokRoutesNeverRequestAnyServiceTier() {
        for (provider in grokProviders()) {
            for (tier in listOf("standard", "fast", "ultrafast")) {
                FastModePrefs.setCachedSpeedTierForTest(tier)
                for (responses in listOf(false, true)) {
                    assertFalse("$tier / responses=$responses", body(provider, responses).has("service_tier"))
                }
            }
        }
    }

    @Test fun changingTheSharedPreferenceCannotEnablePriorityOnAnExistingGrokProvider() {
        for (provider in grokProviders()) {
            for (tier in listOf("standard", "fast", "ultrafast", "standard")) {
                FastModePrefs.setCachedSpeedTierForTest(tier)
                assertFalse(body(provider, false).has("service_tier"))
                assertFalse(body(provider, true).has("service_tier"))
            }
        }
    }

    @Test fun changingTheModelOnAnExistingCompatibleProviderRechecksGptEligibility() {
        val provider = factoryProvider(ProviderType.openAI, LLMModel.gpt61Sol)
        FastModePrefs.setCachedSpeedTierForTest("ultrafast")
        provider.model = model.copy(supportedServiceTiers = listOf("ultrafast"))
        for (responses in listOf(false, true)) assertFalse(body(provider, responses).has("service_tier"))
    }

    @Test fun extraBodyParametersCannotReintroduceServiceTierForGrokOrAnXaiRoute() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (provider in grokProviders() + factoryProvider(ProviderType.xAI, LLMModel.gpt61Sol)) {
            provider.modelOverrides = ModelOverrides(extraBodyParams = JsonObject(mapOf("service_tier" to JsonPrimitive("priority"))))
            for (responses in listOf(false, true)) assertFalse(body(provider, responses).has("service_tier"))
            provider.chatExtraBody = mapOf("service_tier" to "ultrafast")
            for (responses in listOf(false, true)) assertFalse(body(provider, responses).has("service_tier"))
        }
    }
}
