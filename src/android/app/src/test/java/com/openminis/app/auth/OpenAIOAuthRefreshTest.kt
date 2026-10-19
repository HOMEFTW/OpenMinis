package com.openminis.app.auth

import android.content.ContextWrapper
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class OpenAIOAuthRefreshTest {
    private class Manager(url: String) : OpenAIOAuthManager(ContextWrapper(null), "build34-refresh") {
        private val prefs = MemorySharedPreferences()
        override val tokenURL = url
        override fun getEncryptedPrefs() = prefs
    }
    private fun jwt(account: String) = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account))
            .toString().toByteArray(Charsets.UTF_8)) + ".signature"
    private fun stored(id: String, refresh: String = "old-refresh") = JSONObject().put("access_token", "old-access")
        .put("refresh_token", refresh).put("id_token", id).put("expire_at", 1)

    @Test fun refreshPersistsOmittedIdentityAndUpdatesAccountFromReturnedIdentity() = runBlocking {
        MockWebServer().use { server ->
            val manager = Manager(server.url("/token").toString())
            manager.importStoredTokensJson(stored(jwt("account-a")).toString())
            server.enqueue(MockResponse().setBody("""{"access_token":"access-b","expires_in":3600}"""))
            assertTrue(manager.refreshToken())
            assertEquals(jwt("account-a"), JSONObject(manager.exportStoredTokensJson()!!).getString("id_token"))
            assertEquals("account-a", manager.accountId)
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token", "access-c")
                .put("id_token", jwt("account-b")).put("refresh_token", "rotated").toString()))
            assertTrue(manager.refreshToken())
            assertEquals("account-b", manager.accountId)
            assertEquals("rotated", JSONObject(manager.exportStoredTokensJson()!!).getString("refresh_token"))
        }
    }

    @Test fun responseLosingRaceToNewLoginCannotReplaceIdentityOrAccount() = runBlocking {
        MockWebServer().use { server ->
            val manager = Manager(server.url("/token").toString())
            manager.importStoredTokensJson(stored(jwt("old-account")).toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    manager.importStoredTokensJson(stored(jwt("new-login"), "new-refresh").toString())
                    manager.importOAuthString("account_id", "new-login")
                    return MockResponse().setBody(JSONObject().put("access_token", "stale-access")
                        .put("id_token", jwt("stale-account")).toString())
                }
            }
            assertFalse(manager.refreshToken())
            assertEquals("new-login", manager.accountId)
            assertEquals("new-refresh", JSONObject(manager.exportStoredTokensJson()!!).getString("refresh_token"))
        }
    }

    @Test fun rejectedRefreshKeepsCredentialsAndRequiresSignIn() = runBlocking {
        MockWebServer().use { server ->
            val manager = Manager(server.url("/token").toString())
            manager.importStoredTokensJson(stored(jwt("account")).toString())
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            assertFalse(manager.refreshToken())
            assertTrue(manager.needsReauth())
            assertNotNull(manager.exportStoredTokensJson())
        }
    }
}
