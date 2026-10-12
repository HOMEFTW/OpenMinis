package com.openminis.app.provider

import com.openminis.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class RequestProviderCacheTest {
    private class Snapshot(override var model: LLMModel, val endpoint: String?) : LLMProvider {
        override val name = "snapshot"
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ) = LLMResponse("", "end_turn", null)
        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = emptyFlow()
    }

    @Test fun unchangedBindingReusesButAllRequestSettingsRebuild() {
        val cache = RequestProviderCache()
        var instance = ProviderInstance("provider", "Test", ProviderType.openAI, ProviderCredential.apiKey)
        var entry = ModelEntry(instance.id, LLMModel("gpt-6-luna", "Luna", "Custom"))
        var revision = 0L
        var session = "draft"
        var current: LLMProvider? = null
        fun resolve(): LLMProvider = cache.resolve(entry, instance, revision, session, current) {
            Snapshot(entry.model, instance.customBaseURL)
        }.also { current = it }
        val original = resolve()
        assertSame(original, resolve())
        revision++
        assertNotSame(original, resolve())
        val keyed = current
        instance.customBaseURL = "https://example.invalid/v1"
        assertNotSame(keyed, resolve())
        val routed = current
        instance.useResponsesAPI = true
        assertNotSame(routed, resolve())
        val protocol = current
        instance.responseTimeoutSeconds = 30
        assertNotSame(protocol, resolve())
        val timed = current
        entry = entry.copy(overrides = ModelOverrides(maxThinkingLevel = ThinkingLevel.LOW))
        val limited = resolve()
        assertNotSame(timed, limited)
        assertEquals(ThinkingLevel.LOW, limited.model.requestMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, original.model.requestMaxThinkingLevel)
        assertNull((original as Snapshot).endpoint)
        session = "persisted"
        assertNotSame(limited, resolve())
        val stored = current
        current = Snapshot(entry.model, "manual")
        assertNotSame(stored, resolve())
    }
}
