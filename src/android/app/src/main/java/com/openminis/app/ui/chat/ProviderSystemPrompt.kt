package com.openminis.app.ui.chat

import com.openminis.app.BuildConfig
import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider

/** Normalize the prompt when starting, retrying or switching providers in a chat. */
internal fun systemPromptFor(provider: LLMProvider, prompt: String?): String? {
    val oauth = (provider as? AnthropicProvider)?.isOAuth == true
    // Public builds omit this customization. Only Claude OAuth requires it;
    // other routes use the optional value solely to strip it during fallback.
    val prefix = if (oauth) ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        else BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
    if (prompt == null || prefix.isEmpty()) return prompt
    val bare = if (prompt.startsWith(prefix)) prompt.removePrefix(prefix).trimStart('\n') else prompt
    return if (oauth) "$prefix\n\n$bare" else bare
}
