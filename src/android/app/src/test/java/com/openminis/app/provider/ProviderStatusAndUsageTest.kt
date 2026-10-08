package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.gemini.GeminiProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderStatusAndUsageTest {
    @Test fun `only actual 5xx status codes indicate a server error`() {
        assertFalse(LLMError.ProviderError("maximum 5000 tokens").isHttpServerError)
        assertTrue(LLMError.ProviderError("HTML error page", 522).isHttpServerError)
        assertTrue(LLMError.TransientError("overloaded", 529).isHttpServerError)
        assertTrue(LLMError.ProviderError("[503] unavailable").isHttpServerError)
        assertFalse(LLMError.TransientError("empty stream").isHttpServerError)
        assertFalse(LLMError.NetworkError(java.io.IOException("503 mentioned by proxy")).isHttpServerError)
    }

    @Test fun `Gemini cache is not counted twice and thoughts count as output`() {
        val usage = GeminiProvider.parseUsageMetadata(JSONObject()
            .put("promptTokenCount", 1000).put("cachedContentTokenCount", 700)
            .put("candidatesTokenCount", 120).put("thoughtsTokenCount", 80))
        assertEquals(300, usage.inputTokens)
        assertEquals(200, usage.outputTokens)
        assertEquals(700, usage.cacheReadInputTokens)
        assertEquals(1000, usage.latestContextTokens)
    }
}
