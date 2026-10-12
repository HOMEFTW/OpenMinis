package com.openminis.app.provider

import com.openminis.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EntryThinkingCeilingTest {
    private class Capture(override var model: LLMModel) : LLMProvider {
        override val name = "capture"
        var sent: ThinkingLevel? = null
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): LLMResponse {
            sent = thinkingLevel
            return LLMResponse("", "end_turn", null)
        }
        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> {
            sent = thinkingLevel
            return emptyFlow()
        }
    }

    @Test fun bothPublicRequestEntriesClampPersistedOrScheduledMaxToHigh() = runTest {
        val entry = ModelEntry("fixture", LLMModel("gpt-6-luna", "Luna", "Custom"),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH))
        val provider = Capture(entry.model)
        provider.sendMessage(emptyList(), null, 1024, thinkingLevel = ThinkingLevel.MAX)
        assertEquals(ThinkingLevel.HIGH, provider.sent)
        provider.streamMessage(emptyList(), null, 1024, thinkingLevel = ThinkingLevel.MAX)
        assertEquals(ThinkingLevel.HIGH, provider.sent)
        assertEquals(ThinkingLevel.HIGH, entry.effectiveMaxThinkingLevel)
    }

    @Test fun changingTheModelAppliesItsOwnEntryLimit() {
        val provider = Capture(LLMModel("gpt-6-sol", "Sol", "Custom"))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.MAX))
        provider.model = ModelEntry("fixture", LLMModel("gpt-6.1-sol", "Sol", "Custom"),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.LOW)).model
        assertEquals(ThinkingLevel.LOW, provider.clampThinkingLevel(ThinkingLevel.OFF))
        assertEquals(ThinkingLevel.LOW, provider.clampThinkingLevel(ThinkingLevel.MAX))
    }

    @Test fun flashSparseEffortDoesNotRoundAboveTheEntryLimit() {
        val entry = ModelEntry("fixture", LLMModel("deepseek-v4.1-flash", "Flash", "DeepSeek"),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.MEDIUM))
        assertEquals(ThinkingLevel.LOW, entry.model.clampThinkingLevel(ThinkingLevel.MAX))
    }

    @Test fun derivedCeilingIsNotWrittenIntoCatalogWireData() {
        val entry = ModelEntry("fixture", LLMModel("gpt-6-luna", "Luna", "Custom"),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH))
        assertFalse(Json.encodeToString(LLMModel.serializer(), entry.model).contains("entryThinkingCeiling"))
        assertEquals(ThinkingLevel.MAX, entry.baseModel.catalogMaxThinkingLevel)
    }

    @Test fun sparseCatalogIsClampedDownBeforeWireRulesCanRoundItUp() {
        val entry = ModelEntry("fixture", LLMModel("fixture", "Sparse", "Custom",
            supportsReasoning = true, reasoningEffortValues = listOf("high", "max")),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.XHIGH))
        assertEquals(ThinkingLevel.HIGH, entry.model.clampThinkingLevel(ThinkingLevel.MAX))
    }

    @Test fun unsupportedEntryLimitsFailBeforeSendingInsteadOfSilentlyExceedingThem() = runTest {
        val mandatory = Capture(ModelEntry("fixture", LLMModel("gpt-6.1-sol", "Sol", "Custom"),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.OFF)).model)
        assertTrue(runCatching { mandatory.sendMessage(emptyList(), null, 1024) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(mandatory.sent)
        val sparse = Capture(ModelEntry("fixture", LLMModel("sparse", "Sparse", "Custom",
            supportsReasoning = true, reasoningEffortValues = listOf("high", "max")),
            ModelOverrides(maxThinkingLevel = ThinkingLevel.LOW)).model)
        assertTrue(runCatching { sparse.streamMessage(emptyList(), null, 1024, thinkingLevel = ThinkingLevel.MAX) }
            .exceptionOrNull() is IllegalArgumentException)
        assertNull(sparse.sent)
    }

    @Test fun factoryAlsoEnforcesOverridesWhenTheCallerPassesABaseModel() {
        val instance = ProviderInstance("fixture", "Test", ProviderType.openAI, ProviderCredential.apiKey)
        val provider = ProviderFactory.create(instance, "fixture", LLMModel("gpt-6-luna", "Luna", "Custom"),
            overrides = ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.HIGH, provider.clampThinkingLevel(ThinkingLevel.MAX))
    }
}
