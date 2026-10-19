package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CodexRefreshTokensTest {
    private fun jwt(payload: JSONObject) = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.toString().toByteArray(Charsets.UTF_8)) + ".signature"

    @Test fun omittedBlankAndNullIdentityTokensKeepThePriorIdentityAndRefreshToken() {
        val stored = JSONObject().put("id_token", "old-identity").put("refresh_token", "old-refresh")
        for (value in listOf<Any?>(null, "", JSONObject.NULL)) {
            val response = JSONObject().put("access_token", "new-access")
            if (value != null) response.put("id_token", value)
            val merged = mergeCodexRefreshTokens(stored, response)
            assertEquals("old-identity", merged.getString("id_token"))
            assertEquals("old-refresh", merged.getString("refresh_token"))
            assertEquals("new-access", merged.getString("access_token"))
        }
        assertFalse(stored.has("access_token"))
    }

    @Test fun freshIdentityReplacesOldIdentityAndUsesNamespacedAccountId() {
        val id = jwt(JSONObject().put("chatgpt_account_id", "legacy")
            .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "new-account").put("chatgpt_plan_type", "pro")))
        val merged = mergeCodexRefreshTokens(JSONObject().put("id_token", "old"),
            JSONObject().put("id_token", id).put("refresh_token", "rotated"))
        assertEquals(id, merged.getString("id_token"))
        assertEquals("rotated", merged.getString("refresh_token"))
        assertEquals(CodexIdentity("new-account", "pro"), codexIdentity(merged.getString("id_token")))
    }

    @Test fun legacyRootClaimsRemainSupportedWithoutInventingNullStrings() {
        assertEquals(CodexIdentity("account", "plus"), codexIdentity(jwt(JSONObject()
            .put("chatgpt_account_id", "account").put("chatgpt_plan_type", "plus"))))
        assertEquals(CodexIdentity(null, null), codexIdentity(jwt(JSONObject().put("chatgpt_account_id", JSONObject.NULL))))
        assertTrue(runCatching { codexIdentity("broken") }.isFailure)
    }
}
