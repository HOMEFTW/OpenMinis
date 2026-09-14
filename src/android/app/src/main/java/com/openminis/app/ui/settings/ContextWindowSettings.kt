package com.openminis.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User controlled context budget shared by settings UI and chat runtime. */
object ContextWindowSettings {
    private val _changes = MutableStateFlow(DEFAULT)
    val changes = _changes.asStateFlow()

    const val DEFAULT = 105_000
    const val MIN = 8_000
    const val MAX = 1_000_000
    const val PREFS = "chat_preferences"
    const val KEY = "context_window_tokens"

    val presets = listOf(64_000, 105_000, 272_000, 1_000_000)

    /**
     * Read the persisted value and publish it for existing runtime observers.
     * The flow starts with [DEFAULT] because an Android [Context] is not
     * available during object construction.
     */
    fun get(context: Context): Int {
        val value = read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        if (_changes.value != value) _changes.value = value
        return value
    }

    /** Explicit initialization hook for callers that need [changes] at startup. */
    fun initialize(context: Context): Int = get(context)

    /**
     * Persist a value only when it is within the supported range. Keeping this
     * setter as a no-op for invalid values prevents a malformed UI draft from
     * changing the active setting and preserves the existing Unit API.
     */
    fun set(context: Context, tokens: Int) {
        write(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), tokens)
    }

    /** Restore the product default after the caller explicitly confirms it. */
    fun reset(context: Context) {
        reset(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    fun isValid(tokens: Int): Boolean = tokens in MIN..MAX

    /** Parse a decimal token count and reject empty, malformed, or out-of-range input. */
    fun parse(raw: String): Int? {
        val normalized = raw.trim()
        if (normalized.isEmpty() || normalized.any { it !in '0'..'9' }) return null
        return normalized.toLongOrNull()
            ?.takeIf { it in MIN.toLong()..MAX.toLong() }
            ?.toInt()
    }

    /** Display labels for the supported quick choices, including the 1M form. */
    fun formatPreset(tokens: Int): String = when (tokens) {
        64_000 -> "64K"
        105_000 -> "105K"
        272_000 -> "272K"
        1_000_000 -> "1M"
        else -> if (tokens % 1_000 == 0) "${tokens / 1_000}K" else tokens.toString()
    }

    /** Read and normalize a value from a preference store. Kept pure for JVM tests. */
    internal fun read(preferences: SharedPreferences): Int {
        val stored = runCatching { preferences.getInt(KEY, DEFAULT) }.getOrDefault(DEFAULT)
        return stored.coerceIn(MIN, MAX)
    }

    /** Write one validated value and notify observers. Kept pure for JVM tests. */
    internal fun write(preferences: SharedPreferences, tokens: Int): Boolean {
        if (!isValid(tokens)) return false
        preferences.edit().putInt(KEY, tokens).apply()
        _changes.value = tokens
        return true
    }

    internal fun reset(preferences: SharedPreferences) {
        write(preferences, DEFAULT)
    }
}
