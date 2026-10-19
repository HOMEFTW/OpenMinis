package com.openminis.app.provider

import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.openai.CodexModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.OpenAISpeedTiers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Build34SpeedTierTest {
    @After fun resetTier() = FastModePrefs.setCachedEnabledForTest(false)

    private fun provider(model: LLMModel, type: ProviderType = ProviderType.openAI) = ProviderFactory.create(
        ProviderInstance("speed-test", "Speed test", type, ProviderCredential.apiKey), "test-key", model,
    ) as OpenAIProvider

    private fun body(provider: OpenAIProvider, responses: Boolean): JSONObject {
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))
        return if (responses) provider.buildResponsesAPIBody(messages, null, 1024, false)
        else provider.buildRequestBody(messages, null, 1024, false, null, imageParts = emptyList())
    }

    @Test fun parsesBothProviderCapabilityListsWithoutTreatingOtherFieldsAsSupport() {
        val obj = JSONObject("""{"service_tiers":[" FAST ",{"id":" ULTRAFAST "},{"name":"ultrafast"},null,123,""],"additional_speed_tiers":["ultrafast"," priority "]}""")
        assertEquals(listOf("fast", "ultrafast", "priority"), OpenAISpeedTiers.read(obj))
        assertNull(OpenAISpeedTiers.read(JSONObject()))
        assertEquals(emptyList<String>(), OpenAISpeedTiers.read(JSONObject("""{"service_tiers":[]}""")))
    }

    @Test fun codexDiscoveryAndSerializedModelsKeepExplicitCapabilities() {
        val discovered = CodexModelsApi.parseModels("""{"models":[{"slug":"gpt-6.1-sol","additional_speed_tiers":[" ULTRAFAST "]}]}""").single()
        assertEquals(listOf("ultrafast"), discovered.supportedServiceTiers)
        val wire = Json.encodeToString(discovered)
        assertEquals(listOf("ultrafast"), Json.decodeFromString<LLMModel>(wire).supportedServiceTiers)
        assertTrue(JSONObject(wire).has("serviceTiers"))
        assertEquals(listOf("ultrafast"), OpenAISpeedTiers.readSaved(JSONObject(wire)))
        assertNull(Json.decodeFromString<LLMModel>("""{"id":"gpt-4.1","displayName":"GPT","provider":"OpenAI"}""").supportedServiceTiers)
    }

    @Test fun explicitUltrafastRequiresMetadataOrTheExactAstraIdInBothFormats() {
        FastModePrefs.setCachedSpeedTierForTest("ultrafast")
        val supported = listOf(LLMModel.gpt6Astra, LLMModel.gpt61Sol.copy(supportedServiceTiers = listOf("ultrafast")))
        for (model in supported) for (responses in listOf(false, true)) {
            assertEquals(model.id, "ultrafast", body(provider(model), responses).getString("service_tier"))
        }
        for (id in listOf("gpt-6.1-sol", "gpt-6-astra-preview", "gpt-6-astra-2026-10-01", "openai/gpt-6-astra")) {
            val model = LLMModel(id, id, "OpenAI")
            for (responses in listOf(false, true)) {
                assertEquals(id, "priority", body(provider(model), responses).getString("service_tier"))
            }
        }
    }

    @Test fun tierChangesApplyToCachedProvidersAndFastNeverPromotesItselfToUltrafast() {
        val model = LLMModel.gpt61Sol.copy(supportedServiceTiers = listOf("ultrafast"))
        for (responses in listOf(false, true)) {
            val provider = provider(model, ProviderType.openAIResponses)
            FastModePrefs.setCachedSpeedTierForTest("ultrafast")
            assertEquals("ultrafast", body(provider, responses).getString("service_tier"))
            FastModePrefs.setCachedEnabledForTest(true)
            assertEquals("priority", body(provider, responses).getString("service_tier"))
            FastModePrefs.setCachedEnabledForTest(false)
            assertFalse(body(provider, responses).has("service_tier"))
        }
        FastModePrefs.setCachedSpeedTierForTest("ultrafast")
        val oauth = OpenAIProvider(oauthTokenProvider = { "test-token" }, model = model)
        assertEquals("ultrafast", body(oauth, true).getString("service_tier"))
    }

    @Test fun ultrafastDoesNotLeakToOtherProtocolsOrImageOnlyModelsAndXaiKeepsPriority() {
        FastModePrefs.setCachedSpeedTierForTest("ultrafast")
        val model = LLMModel.gpt6Astra.copy(supportedServiceTiers = listOf("ultrafast"))
        for (type in listOf(ProviderType.openRouter, ProviderType.kimiCode)) {
            for (responses in listOf(false, true)) assertFalse(body(provider(model, type), responses).has("service_tier"))
        }
        val imageOnly = model.copy(outputModalities = listOf("image"))
        val nonGpt = LLMModel("custom-model", "Custom", "Custom", supportedServiceTiers = listOf("ultrafast"))
        val grok = LLMModel("grok-4", "Grok", "xAI", supportedServiceTiers = listOf("ultrafast"))
        for (responses in listOf(false, true)) {
            assertFalse(body(provider(imageOnly), responses).has("service_tier"))
            assertFalse(body(provider(nonGpt), responses).has("service_tier"))
            assertEquals("priority", body(provider(grok, ProviderType.xAI), responses).getString("service_tier"))
        }
    }
}
