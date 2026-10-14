package com.openminis.app.provider.usage

import org.json.JSONObject

/** Only a stored OAuth grant identifies the subscription account; an API key is not a grant. */
internal fun kimiSubscriptionOAuthToken(tokens: JSONObject?, nowMillis: Long): String? {
    tokens ?: return null
    val expiresAt = tokens.optLong("expire_at", 0)
    if (expiresAt > 0 && nowMillis >= expiresAt) return null
    return (tokens.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
}
