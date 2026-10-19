package com.openminis.app.ui.scheduled

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.ui.settings.getAppearancePrefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object ScheduledTaskBadgePreference {
    const val KEY = "scheduled.showHomeBadge"

    fun isEnabled(context: Context): Boolean = getAppearancePrefs(context).getBoolean(KEY, true)

    fun setEnabled(context: Context, value: Boolean) {
        getAppearancePrefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun observe(context: Context) = callbackFlow {
        val prefs = getAppearancePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(prefs.getBoolean(KEY, true))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(KEY, true))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
