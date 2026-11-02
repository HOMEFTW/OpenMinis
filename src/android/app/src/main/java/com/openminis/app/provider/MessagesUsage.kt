package com.openminis.app.provider

import com.openminis.app.data.model.LLMUsage
import com.openminis.app.usage.ProviderUsageParser
import org.json.JSONObject

/** Messages bills cache separately; the fork's context guard counts the full prompt. */
internal fun parseMessagesUsage(json: JSONObject): LLMUsage {
    val chunk = ProviderUsageParser.anthropic(json)
    val context = chunk.input?.let { it + (chunk.cacheRead ?: 0L) + (chunk.cacheWrite ?: 0L) }
    return chunk.copy(contextTokens = context).toLLMUsage()
}
