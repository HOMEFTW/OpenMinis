package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object UpdatePreferences {
    private const val PREFS = "update_preferences"
    private const val CHANNEL = "updates.channel"
    private const val AUTO_CHECK = "updates.autoCheck"
    private val checkLock = Mutex()
    private val _availableUpdate = MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)
    val availableUpdate = _availableUpdate.asStateFlow()
    data class Settings(val channel: String, val autoCheck: Boolean)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun channel(context: Context): String = prefs(context).getString(CHANNEL, "stable")?.takeIf { it == "beta" } ?: "stable"
    fun autoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(AUTO_CHECK, true)
    fun snapshot(context: Context) = Settings(channel(context), autoCheckEnabled(context))
    fun observe(context: Context) = callbackFlow {
        val preferences = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot(context)) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun setChannel(context: Context, value: String) {
        require(value == "stable" || value == "beta")
        prefs(context).edit().putString(CHANNEL, value).apply()
        _availableUpdate.value = null
    }

    fun setAutoCheckEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(AUTO_CHECK, value).apply()
        if (!value) _availableUpdate.value = null
    }

    internal fun shouldCheck(lastCheck: Long, now: Long, channel: String): Boolean {
        val interval = (if (channel == "beta") 6L else 24L) * 60 * 60 * 1000
        return lastCheck <= 0L || now < lastCheck || now - lastCheck >= interval
    }

    /** Only offers an update; installing still requires the user's explicit tap. */
    suspend fun onForeground(context: Context, now: Long = System.currentTimeMillis()) = checkLock.withLock {
        if (!autoCheckEnabled(context)) return@withLock
        val selectedChannel = channel(context)
        val key = "lastCheck.$selectedChannel"
        val preferences = prefs(context)
        if (!shouldCheck(preferences.getLong(key, 0L), now, selectedChannel)) return@withLock
        preferences.edit().putLong(key, now).apply()
        val result = UpdateChecker.check(includePrereleases = selectedChannel == "beta")
        if (!autoCheckEnabled(context) || channel(context) != selectedChannel) return@withLock
        when (result) {
            is UpdateChecker.CheckResult.UpdateAvailable -> _availableUpdate.value = result
            UpdateChecker.CheckResult.UpToDate -> _availableUpdate.value = null
            else -> Unit
        }
    }
}
