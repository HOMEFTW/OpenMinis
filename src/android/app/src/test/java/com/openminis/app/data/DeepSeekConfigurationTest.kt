package com.openminis.app.data

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.*
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.selectableThinkingLevels
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DeepSeekConfigurationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun native(base: String? = null, appendV1: Boolean = false) = ProviderInstance(
        id = "native", label = "DeepSeek", providerType = ProviderType.deepSeek,
        credentialType = ProviderCredential.apiKey, customBaseURL = base,
        appendV1Suffix = appendV1, customUserAgent = "DeepSeek-test", responseTimeoutSeconds = 900,
        createdAt = 1_700_000_000_000,
    )

    @Test fun providerTypeHasStableJsonAndDatabaseName() {
        assertEquals("\"deepSeek\"", json.encodeToString(ProviderType.serializer(), ProviderType.deepSeek))
        assertEquals(ProviderType.deepSeek, json.decodeFromString(ProviderType.serializer(), "\"deepSeek\""))
        assertEquals(ProviderType.deepSeek, ProviderType.decoded("deepSeek"))
        assertTrue(ProviderType.deepSeek.isUsable)
        assertEquals(ProviderType.unsupported, ProviderType.decoded("futureProvider"))

        val relay = ProviderInstance("relay", "Existing relay", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://relay.example/deepseek", appendV1Suffix = true, useResponsesAPI = true,
            createdAt = 1_700_000_000_000)
        val entry = ModelEntry(providerInstanceId = "native", baseModel = DeepSeekModels.all.first(),
            overrides = ModelOverrides(displayName = "My Flash", contextWindow = 123_456), isHidden = true)
        val custom = ModelEntry(providerInstanceId = "native", baseModel = LLMModel("custom-model", "Custom", "DeepSeek",
            inputModalities = listOf("text")), isCustom = true)
        val config = ProviderConfig(instances = mutableListOf(native(), relay), modelEntries = mutableListOf(entry, custom))
        val decoded = json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), config))
        val snapshot = decoded.toSnapshot(json)
        assertEquals(listOf("deepSeek", "openAI"), snapshot.instances.map { it.providerType })
        val restored = snapshot.toProviderConfig(json)
        assertEquals(config.instances, restored.instances)
        assertEquals(entry.overrides, restored.modelEntries.first().overrides)
        assertTrue(restored.modelEntries.first().isHidden)
        assertEquals(entry.baseModel, restored.modelEntries.first().baseModel)
        assertTrue(restored.modelEntries.last().isCustom)
        assertEquals("https://relay.example/deepseek/v1", restored.instances.last().effectiveBaseURL)
        assertTrue(restored.instances.last().useResponsesAPI)
    }

    @Test fun officialRootUsesAnthropicRouteWithoutAutomaticVersionSuffix() {
        val default = ProviderInstance("default", "DeepSeek", ProviderType.deepSeek, ProviderCredential.apiKey)
        assertFalse(default.appendV1Suffix)
        assertNull(default.effectiveBaseURL)
        assertNull(native("   ").effectiveBaseURL)
        for (root in listOf("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1/",
            "  https://API.DEEPSEEK.COM/  ", DeepSeekModels.DEFAULT_BASE_URL, "${DeepSeekModels.DEFAULT_BASE_URL}/")) {
            for (append in listOf(false, true)) {
                assertEquals(root, DeepSeekModels.DEFAULT_BASE_URL, native(root, append).effectiveBaseURL)
            }
        }
        assertEquals("https://relay.example/anthropic/", native("https://relay.example/anthropic/", true).effectiveBaseURL)
        assertEquals("https://relay.example/v1/", native("https://relay.example/v1/", true).effectiveBaseURL)
        assertEquals("https://api.deepseek.com.relay.example", native("https://api.deepseek.com.relay.example", true).effectiveBaseURL)
        assertEquals(DeepSeekModels.DEFAULT_BASE_URL, native("https://api.deepseek.com:443/v1").effectiveBaseURL)
        for (invalid in listOf("http://api.deepseek.com", "https://user@api.deepseek.com",
            "https://api.deepseek.com?key=value/", "https://api.deepseek.com#fragment/", "https://api.deepseek.com:8443/v1/")) {
            assertEquals(invalid, invalid, native(invalid).effectiveBaseURL)
        }
        for (type in listOf(ProviderType.openAI, ProviderType.anthropic)) {
            val old = ProviderInstance("old", "Old", type, ProviderCredential.apiKey,
                customBaseURL = "https://api.deepseek.com", appendV1Suffix = true)
            assertEquals("https://api.deepseek.com/v1", old.effectiveBaseURL)
            assertEquals(type, json.decodeFromString(ProviderInstance.serializer(), json.encodeToString(ProviderInstance.serializer(), old)).providerType)
        }
    }

    @Test fun nativeConfigurationExcludesOpenAiOnlyOptionsAndRequiresAKey() {
        val instance = native("https://relay.example", true).copy(useResponsesAPI = true, azureMode = true)
        assertFalse(instance.supportsAzureMode)
        assertFalse(instance.supportsImageEndpointSetting)
        assertFalse(instance.supportsCustomThinkingRules)
        assertFalse(instance.allowsEmptyAPIKey)
        assertTrue(ProviderInstance("old", "Old", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://relay.example").allowsEmptyAPIKey)
    }

    @Test fun advisoryCatalogHasExplicitModalitiesLimitsAndSparseThinkingLevels() {
        val models = ProviderType.deepSeek.builtInModels
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
        assertEquals(listOf("DeepSeek-V41-Flash", "DeepSeek-V4-Pro"), models.map { it.displayName })
        assertEquals(listOf("text", "image"), models.first().inputModalities)
        assertEquals(listOf("text"), models.last().inputModalities)
        for (model in models) {
            val cached = json.decodeFromString(LLMModel.serializer(), json.encodeToString(LLMModel.serializer(), model))
            val effective = ModelEntry("native", cached).model
            assertEquals("DeepSeek", effective.provider)
            assertEquals(1_000_000, effective.contextWindow)
            assertEquals(256_000, effective.maxOutputTokens)
            assertEquals(true, effective.supportsReasoning)
            assertEquals(listOf("text"), effective.outputModalities)
            assertEquals(model.inputModalities, effective.inputModalities)
            assertEquals(listOf("low", "high", "max"), effective.reasoningEffortValues)
            assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), effective.selectableThinkingLevels)
            assertEquals(ThinkingLevel.MAX, effective.catalogMaxThinkingLevel)
        }
    }
}
