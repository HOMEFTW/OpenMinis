package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogSeedTest {
    private fun instance(type: ProviderType, base: String?, oauth: Boolean = false) = ProviderInstance(
        id = "fixture", label = "Fixture", providerType = type,
        credentialType = if (oauth) ProviderCredential.oauth else ProviderCredential.apiKey,
        customBaseURL = base,
    )

    @Test fun `custom Anthropic and OpenAI catalogs do not get vendor model seeds`() {
        for (type in listOf(ProviderType.anthropic, ProviderType.openAI, ProviderType.openAIResponses)) {
            assertFalse(shouldSeedBuiltInModels(instance(type, "https://relay.example/anthropic")))
            assertFalse(shouldSeedBuiltInModels(instance(type, "http://localhost:1234/v1")))
        }
    }

    @Test fun `official endpoints and OAuth keep their existing seed catalog`() {
        for ((type, base) in listOf(
            ProviderType.anthropic to "https://api.anthropic.com/v1",
            ProviderType.openAI to "https://api.openai.com/v1",
            ProviderType.openAIResponses to "https://api.openai.com/v1",
        )) {
            assertTrue(shouldSeedBuiltInModels(instance(type, null)))
            assertTrue(shouldSeedBuiltInModels(instance(type, base)))
            assertTrue(shouldSeedBuiltInModels(instance(type, "https://relay.example/v1", oauth = true)))
        }
    }

    @Test fun `official host in a relay path or subdomain does not select official models`() {
        for (base in listOf("https://api.anthropic.com.relay.example/v1", "https://relay.example/api.anthropic.com/v1")) {
            assertFalse(shouldSeedBuiltInModels(instance(ProviderType.anthropic, base)))
        }
    }

    @Test fun `native provider catalogs retain their seed policy`() {
        for (type in listOf(ProviderType.deepSeek, ProviderType.xAI, ProviderType.gemini)) {
            assertTrue(shouldSeedBuiltInModels(instance(type, "https://relay.example/v1")))
        }
    }
}
