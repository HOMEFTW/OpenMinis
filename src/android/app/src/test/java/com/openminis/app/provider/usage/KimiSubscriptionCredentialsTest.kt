package com.openminis.app.provider.usage

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KimiSubscriptionCredentialsTest {
    @Test fun `a hand entered API key without an OAuth grant shows authentication required without a request`() = runBlocking {
        val keyOnly = JSONObject().put("api_key", "test-only-api-key").put("manual_bearer", "test-only-proxy-key")
        assertNull(kimiSubscriptionOAuthToken(keyOnly, 1000))
        val state = KimiSubscriptionUsageClient().load { kimiSubscriptionOAuthToken(keyOnly, 1000) }
        assertEquals(KimiUsageFailure.AUTHENTICATION, (state as KimiUsageState.Failed).reason)
        assertNull(state.usage)
    }

    @Test fun `subscription credentials are the OAuth account token rather than a manual proxy override`() {
        val grant = JSONObject().put("access_token", "test-only-oauth-account")
            .put("api_key", "test-only-api-key").put("manual_bearer", "test-only-proxy-key")
            .put("expire_at", 2000)
        assertEquals("test-only-oauth-account", kimiSubscriptionOAuthToken(grant, 1000))
        assertNull(kimiSubscriptionOAuthToken(grant, 2000))
        assertNull(kimiSubscriptionOAuthToken(null, 1000))
        assertNull(kimiSubscriptionOAuthToken(JSONObject().put("access_token", " "), 1000))
        assertNull(kimiSubscriptionOAuthToken(JSONObject().put("access_token", JSONObject.NULL), 1000))
    }
}
