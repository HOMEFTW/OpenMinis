package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-codex-fast-mode] App-level persisted Fast Mode toggle (mirrors iOS
 * UserDefaults key `codexFastModeEnabled`, commit fb671083). Unlike Enhanced
 * Cache the flag IS durable and shared across sessions (user-confirmed on
 * iOS): switching to another chat reads the same enabled state.
 *
 * The provider layer has no Context, and iOS deliberately reads the flag at
 * REQUEST-BUILD time so a flip applies to the very next request of an ongoing
 * session — including offload / title-gen calls that never pass through the
 * ChatViewModel. To reproduce that, [prime] captures the application context
 * once at app startup (MinisApp.onCreate) and warms a shared state flow; the
 * context-free [isEnabled] is then safe to call from any request builder.
 */
object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_ENABLED = "codexFastModeEnabled"
    private const val KEY_SPEED_TIER = "speedTier"

    @Volatile
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _speedTier = MutableStateFlow("standard")
    val speedTier: StateFlow<String> = _speedTier.asStateFlow()
    private val stateLock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) = synchronized(stateLock) {
        appContext = context.applicationContext
        val stored = prefs(context)
        val legacy = stored.getBoolean(KEY_ENABLED, false)
        val tier = runCatching { stored.getString(KEY_SPEED_TIER, null) }.getOrNull()
            ?.takeIf { it in setOf("standard", "fast", "ultrafast") }
        val chosen = if (tier == null || (stored.contains(KEY_ENABLED) && legacy != (tier != "standard"))) {
            if (legacy) "fast" else "standard"
        } else tier
        updateCachedTier(chosen)
    }

    /**
     * Context-free read for request builders. Falls back to the warm cache
     * value (false before [prime] has ever run — matches an un-toggled fresh
     * install, and prime runs before any request can be built).
     */
    fun isEnabled(): Boolean {
        return _enabled.value
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        setSpeedTier(context, if (enabled) "fast" else "standard")
    }

    /** Config's explicit tier and the legacy checkbox always update the same shared state. */
    fun setSpeedTier(context: Context, value: String) = synchronized(stateLock) {
        val tier = checkedTier(value)
        updateCachedTier(tier)
        prefs(context).edit().putString(KEY_SPEED_TIER, tier)
            .putBoolean(KEY_ENABLED, tier != "standard").apply()
    }

    private fun checkedTier(value: String): String = value.trim().lowercase(java.util.Locale.ROOT).also {
        require(it in setOf("standard", "fast", "ultrafast")) { "Unknown speed tier" }
    }

    private fun updateCachedTier(tier: String) {
        _speedTier.value = tier
        _enabled.value = tier != "standard"
    }

    /**
     * [T-android-xai-priority] Set ONLY the in-memory cache, with no Context and
     * no persistence. Exists so JVM unit tests can exercise the real
     * [isEnabled] path that request builders consult — there is no Robolectric
     * in this module, so the Context-taking [setEnabled] is unusable there, and
     * asserting against a hand-rolled fake would test the fake instead of the
     * shipping code. Not for production use: a write here is lost on restart.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        synchronized(stateLock) { updateCachedTier(if (enabled) "fast" else "standard") }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedSpeedTierForTest(value: String) {
        synchronized(stateLock) { updateCachedTier(checkedTier(value)) }
    }
}
