package com.openminis.app.ui.chat

import com.openminis.app.BuildConfig
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.ProviderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProviderSystemPromptTest(private val type: ProviderType, private val modelId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun routes(): List<Array<Any>> = listOf(
            arrayOf(ProviderType.openAI, "gpt-6-astra"),
            arrayOf(ProviderType.openAIResponses, "gpt-6.1-sol"),
            arrayOf(ProviderType.deepSeek, "deepseek-v4-flash"),
            arrayOf(ProviderType.gemini, "gemini-2.5-flash"),
            arrayOf(ProviderType.anthropic, "claude-sonnet-4-5"),
            arrayOf(ProviderType.xAI, "grok-4"),
            arrayOf(ProviderType.openRouter, "openai/gpt-6.1-sol"),
        )
    }

    private fun provider() = ProviderFactory.create(
        ProviderInstance(
            id = "prompt-test", label = "Prompt test", providerType = type,
            credentialType = ProviderCredential.apiKey,
        ),
        "synthetic-key", LLMModel(modelId, modelId, type.name),
    )

    @Test fun `chat prompts work without Claude OAuth customization`() {
        val provider = provider()
        assertEquals("Follow the user instructions.", systemPromptFor(provider, "Follow the user instructions."))
        assertEquals("", systemPromptFor(provider, ""))
        assertNull(systemPromptFor(provider, null))
    }

    @Test fun `retries and switches between API key providers preserve the prompt`() {
        val prompt = "Answer in Chinese.\n\nKeep this spacing."
        val primary = provider()
        val other = ProviderFactory.create(
            ProviderInstance(id = "fallback-test", label = "Fallback", providerType = ProviderType.anthropic,
                credentialType = ProviderCredential.apiKey),
            "synthetic-key", LLMModel.claudeHaiku45,
        )
        val initial = systemPromptFor(primary, prompt)
        val retry = systemPromptFor(primary, initial)
        val fallback = systemPromptFor(other, retry)
        assertEquals(prompt, systemPromptFor(primary, fallback))
    }

    @Test fun `a configured Claude OAuth prefix is removed for non OAuth routes`() {
        // Public builds have no prefix. Configured builds must still strip it
        // when a Claude OAuth request falls back to an API-key provider.
        val prefix = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        val prompt = if (prefix.isEmpty()) "Keep the task." else "$prefix\n\nKeep the task."
        assertEquals("Keep the task.", systemPromptFor(provider(), prompt))
    }
}
