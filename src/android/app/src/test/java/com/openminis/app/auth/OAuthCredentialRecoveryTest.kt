package com.openminis.app.auth

import android.content.ContextWrapper
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OAuthCredentialRecoveryTest {
    private class Manager : OAuthManager(ContextWrapper(null), "recovery-test") {
        val prefs = MemorySharedPreferences()
        override val authURL = "https://example.invalid/auth"
        override val tokenURL = "https://example.invalid/token"
        override val clientId = "test"
        override val clientSecret: String? = null
        override val callbackPort = 1234
        override val redirectPath = "/callback"
        override val scopes = ""
        override fun getEncryptedPrefs() = prefs
        var refreshAction: () -> Boolean = { false }
        override suspend fun refreshToken() = refreshAction()
        fun reject(token: String) = markNeedsReauth(token)
        fun complete(token: String, result: JSONObject) = saveRefreshedTokens(token, result)
    }

    private fun bundle(access: String, refresh: String, expiry: Long = System.currentTimeMillis() + 60000) =
        JSONObject().put("access_token", access).put("refresh_token", refresh).put("expire_at", expiry)

    @Test fun expiredTransientFailureKeepsCredentials() = runBlocking {
        val manager = Manager()
        manager.importStoredTokensJson(bundle("old", "refresh", 1).toString())
        assertNull(manager.validAccessToken())
        assertNotNull(manager.exportStoredTokensJson())
        assertFalse(manager.needsReauth())
    }

    @Test fun rejectionRetainsCredentialsUntilNewLogin() = runBlocking {
        val manager = Manager()
        manager.importStoredTokensJson(bundle("old", "refresh").toString())
        manager.refreshAction = { manager.reject("refresh"); false }
        assertNull(manager.validAccessToken())
        assertTrue(manager.needsReauth())
        assertEquals("old", JSONObject(manager.exportStoredTokensJson()!!).getString("access_token"))
        manager.importStoredTokensJson(bundle("new", "new-refresh").toString())
        assertFalse(manager.needsReauth())
        manager.reject("refresh")
        assertFalse(manager.needsReauth())
    }

    @Test fun oldRefreshCannotOverwriteNewLoginOrResurrectLogout() {
        val manager = Manager()
        manager.importStoredTokensJson(bundle("new", "new-refresh").toString())
        assertFalse(manager.complete("old-refresh", bundle("stale", "rotated")))
        assertEquals("new", JSONObject(manager.exportStoredTokensJson()!!).getString("access_token"))
        manager.logout()
        assertFalse(manager.complete("new-refresh", bundle("stale", "rotated")))
        assertNull(manager.exportStoredTokensJson())
    }

    @Test fun logoutDuringRefreshDoesNotReturnCapturedToken() = runBlocking {
        val manager = Manager()
        manager.importStoredTokensJson(bundle("old", "refresh").toString())
        manager.refreshAction = { manager.logout(); false }
        assertNull(manager.validAccessToken())
    }

    @Test fun manualBearerOverridesRejectedOAuth() = runBlocking {
        val manager = Manager()
        manager.importStoredTokensJson(bundle("old", "refresh").toString())
        manager.reject("refresh")
        manager.saveManualBearerToken("manual")
        assertFalse(manager.needsReauth())
        assertEquals("manual", manager.validAccessToken())
    }
}
