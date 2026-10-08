package com.openminis.app.data.model

/** Advisory catalog from the official API-key adapter; discovery does not call /models. */
object DeepSeekModels {
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"

    fun normalizeBaseURL(baseURL: String?): String? {
        val base = baseURL?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { java.net.URI(base) }.getOrNull()
        return if (uri != null && uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.deepseek.com", ignoreCase = true) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in setOf(-1, 443) && uri.path.orEmpty().trim('/') in setOf("", "v1", "anthropic")
        ) DEFAULT_BASE_URL else base
    }

    val all: List<LLMModel> = listOf(
        LLMModel(
            id = "deepseek-flash",
            displayName = "DeepSeek-V41-Flash",
            provider = "DeepSeek",
            contextWindow = 1_000_000,
            maxOutputTokens = 256_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        ),
        LLMModel(
            id = "deepseek-v4-pro",
            displayName = "DeepSeek-V4-Pro",
            provider = "DeepSeek",
            contextWindow = 1_000_000,
            maxOutputTokens = 256_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
            inputModalities = listOf("text"),
            outputModalities = listOf("text"),
        ),
    )
}
