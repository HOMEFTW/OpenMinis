package com.openminis.app.auth

import android.content.ContextWrapper
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CopilotCredentialRecoveryTest {
    private class Manager : CopilotOAuthManager(ContextWrapper(null), "copilot-recovery") {
        val prefs = MemorySharedPreferences()
        override fun getEncryptedPrefs() = prefs
    }

    private fun blob(account: String, session: String? = null) = JSONObject().apply {
        put("githubToken", account)
        if (session != null) {
            put("sessionToken", session)
            put("sessionExpiresAt", System.currentTimeMillis() / 1000 + 3600)
        }
    }.toString()

    @Test fun ownCredentialKeysReportAuthenticatedAndExportThroughGenericInterface() {
        val manager = Manager()
        manager.importStoredTokensJson(blob("account", "session"))
        assertTrue(manager.isAuthenticated())
        assertEquals("account", JSONObject(manager.exportStoredTokensJson()!!).getString("githubToken"))
    }

    @Test fun switchingAccountsClearsThePreviousSession() {
        val manager = Manager()
        manager.importStoredTokensJson(blob("old-account", "old-session"))
        manager.importStoredTokensJson(blob("new-account"))
        val exported = JSONObject(manager.exportStoredTokensJson()!!)
        assertEquals("new-account", exported.getString("githubToken"))
        assertFalse(exported.has("sessionToken"))
        assertFalse(exported.has("sessionExpiresAt"))
    }

    @Test fun staleExchangeCannotOverwriteAnotherAccountOrResurrectLogout() {
        val manager = Manager()
        manager.importStoredTokensJson(blob("new-account", "current-session"))
        val stale = CopilotDeviceFlow.SessionToken("stale-session", 9999999999)
        assertFalse(manager.saveSessionTokenIfCurrent("old-account", stale))
        assertEquals("current-session", JSONObject(manager.exportStoredTokensJson()!!).getString("sessionToken"))
        manager.logout()
        assertFalse(manager.saveSessionTokenIfCurrent("new-account", stale))
        assertFalse(manager.isAuthenticated())
        assertNull(manager.exportStoredTokensJson())
    }

    @Test fun currentSessionIsReturnedWithoutAnExchangeAndLogoutClearsBothTiers() = runBlocking {
        val manager = Manager()
        manager.importStoredTokensJson(blob("account", "session"))
        assertEquals("session", manager.validAccessToken())
        manager.logout()
        assertNull(manager.validAccessToken())
        assertNull(manager.exportStoredTokensJson())
    }
}
