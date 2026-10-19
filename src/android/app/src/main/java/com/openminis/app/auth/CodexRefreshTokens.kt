package com.openminis.app.auth

import org.json.JSONObject
import java.util.Base64

/** Refresh responses may omit the identity token; that does not discard the signed identity. */
internal fun mergeCodexRefreshTokens(stored: JSONObject, response: JSONObject): JSONObject =
    JSONObject(response.toString()).apply {
        for (key in listOf("refresh_token", "id_token")) {
            if (isNull(key) || optString(key, "").isBlank()) {
                stored.optString(key, "").takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

internal data class CodexIdentity(val accountId: String?, val planType: String?)

internal fun codexIdentity(token: String): CodexIdentity {
    val payload = token.split('.').getOrNull(1) ?: error("Missing identity payload")
    val root = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
    val auth = root.optJSONObject("https://api.openai.com/auth")
    fun claim(key: String): String? = (auth?.opt(key) as? String)?.takeIf { it.isNotBlank() }
        ?: (root.opt(key) as? String)?.takeIf { it.isNotBlank() }
    return CodexIdentity(claim("chatgpt_account_id"), claim("chatgpt_plan_type"))
}
