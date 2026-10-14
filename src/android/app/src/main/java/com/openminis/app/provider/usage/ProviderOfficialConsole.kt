package com.openminis.app.provider.usage

import java.net.URI
import java.util.Locale

/** The configured URL identifies the provider only; it is never the link target. */
internal fun officialProviderConsole(baseURL: String?): String? {
    val uri = runCatching { URI(baseURL?.trim() ?: return null) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
    return when (uri.host?.lowercase(Locale.ROOT)) {
        "api.minimaxi.com" -> "https://platform.minimaxi.com/console/usage"
        "api.minimax.io" -> "https://platform.minimax.io/user-center/payment/token-plan"
        "opencode.ai" -> "https://opencode.ai/console"
        else -> null
    }
}
