package com.openminis.app.data.model

enum class ChatWallpaper { DEEPSEEK, GPT, CLAUDE }

data class ChatWallpaperSession(
    val enabled: Boolean = true,
    val transparencyOverride: Int? = null,
) {
    /** 0 shows the whole image; 100 makes it fully transparent. */
    fun effectiveTransparency(default: Int): Int =
        (transparencyOverride ?: default).coerceIn(0, 100)
}

data class ChatWallpaperState(
    val defaultTransparency: Int = 75,
    val sessions: Map<String, ChatWallpaperSession> = emptyMap(),
) {
    fun forSession(id: String): ChatWallpaperSession = sessions[id] ?: ChatWallpaperSession()
}

private val wallpaperVersionSuffix = Regex("""v\d+(?:\.\d+)*""")
private val deepSeekWallpaperId = Regex("""^deepseek(?:$|[-_.])""")
private val gptWallpaperId = Regex(
    """^(?:gpt-?\d+(?:\.\d+)?o?|o\d+)(?:$|[-:])|^(?:chatgpt|codex)(?:$|[-:])""",
)
private val claudeWallpaperId = Regex(
    """^(?:claude|(?:(?:us|eu|apac|global)\.)?anthropic\.claude)(?:$|-)""",
)

/** Resolve the actual model ID, including gateway namespaces and Bedrock aliases. */
fun resolveChatWallpaper(modelId: String?): ChatWallpaper? {
    val parts = modelId?.trim()?.lowercase()?.split('/') ?: return null
    val name = if (wallpaperVersionSuffix.matches(parts.last())) {
        parts.getOrNull(parts.lastIndex - 1) ?: return null
    } else {
        parts.last()
    }
    return when {
        deepSeekWallpaperId.containsMatchIn(name) -> ChatWallpaper.DEEPSEEK
        gptWallpaperId.containsMatchIn(name) -> ChatWallpaper.GPT
        claudeWallpaperId.containsMatchIn(name) -> ChatWallpaper.CLAUDE
        else -> null
    }
}
