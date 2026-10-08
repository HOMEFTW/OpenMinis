package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.anthropic.AnthropicProvider
import org.junit.Assert.*
import org.junit.Test

class AnthropicStreamRegressionTest {
    @Test fun `Claude 5 off never emits the unsupported disabled literal`() {
        assertTrue(AnthropicProvider.modelAcceptsExplicitThinkingDisabled("claude-opus-4-6"))
        assertFalse(AnthropicProvider.modelAcceptsExplicitThinkingDisabled("claude-sonnet-5"))
        assertFalse(AnthropicProvider.modelAcceptsExplicitThinkingDisabled("claude-sonnet-4-5"))
    }

    @Test fun `midstream errors preserve fallback and authentication categories`() {
        assertTrue(AnthropicProvider.streamError("overloaded_error", "busy") is LLMError.TransientError)
        assertTrue(AnthropicProvider.streamError("api_error", "failed") is LLMError.TransientError)
        assertTrue(AnthropicProvider.streamError("rate_limit_error", "slow down") is LLMError.RateLimited)
        assertTrue(AnthropicProvider.streamError("authentication_error", "expired") is LLMError.InvalidApiKey)
        assertTrue(AnthropicProvider.streamError("unknown", "failed") is LLMError.ProviderError)
    }
}
