package com.openminis.app.data

import android.content.Context
import com.openminis.app.BuildConfig
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compatibility API for the fork's settings, backup and foreground observer. */
object UpdatePreferences {
    private val checkLock = Mutex()
    val availableUpdate = UpdateStateStore.availableUpdate
    data class Settings(val channel: String, val autoCheck: Boolean)

    fun channel(context: Context): String { UpdateStateStore.init(context); return UpdateStateStore.channel.value.id }
    fun autoCheckEnabled(context: Context): Boolean { UpdateStateStore.init(context); return UpdateStateStore.autoEnabled.value }
    fun snapshot(context: Context) = Settings(channel(context), autoCheckEnabled(context))
    fun observe(context: Context) = run {
        UpdateStateStore.init(context)
        combine(UpdateStateStore.channel, UpdateStateStore.autoEnabled) { channel, enabled -> Settings(channel.id, enabled) }
    }
    fun setChannel(context: Context, value: String) {
        require(value == "stable" || value == "beta")
        UpdateStateStore.setChannel(context, UpdatePolicy.Channel.parse(value))
    }
    fun setAutoCheckEnabled(context: Context, value: Boolean) = UpdateStateStore.setAutoEnabled(context, value)
    internal fun shouldCheck(lastCheck: Long, now: Long, channel: String): Boolean =
        lastCheck <= 0L || now < lastCheck || now - lastCheck >= UpdatePolicy.intervalMs(UpdatePolicy.Channel.parse(channel))

    suspend fun onForeground(context: Context, now: Long = System.currentTimeMillis()) = checkLock.withLock {
        UpdateStateStore.init(context)
        val decision = UpdatePolicy.autoDecision(
            now, UpdateStateStore.throttleState(context), UpdateStateStore.channel.value, BuildConfig.VERSION_CODE,
            BuildConfig.AUTO_UPDATE_CHECK, UpdateStateStore.autoEnabled.value, UpdateChecker.isChecking,
        )
        if (decision == UpdatePolicy.AutoDecision.CHECK || decision == UpdatePolicy.AutoDecision.INVALIDATE_AND_CHECK)
            UpdateChecker.check(context, reason = "auto-foreground")
    }
}
