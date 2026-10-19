package com.openminis.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Optional Android settings member; existing iOS categories and records stay unchanged. */
internal object BackupAppPreferences {
    const val FILE_NAME = "android_preferences.json"
    private const val UPDATE_PREFS = "update_preferences"
    private const val CHANNEL = "updates.channel"
    private const val AUTO_CHECK = "updates.autoCheck"

    data class UpdateSettings(val channel: String, val autoCheck: Boolean)
    data class RestorePlan(
        val settings: UpdateSettings,
        val updated: Int = 0,
        val skipped: Int = 0,
        val unreadable: Int = 0,
    )

    // An explicit key whitelist: lastCheck.* and future device-local state must not migrate.
    // Snapshot effective values too, so restoring a default stable/true device resets beta/false.
    fun snapshot(settings: UpdateSettings): JsonObject = buildJsonObject {
        put(UPDATE_PREFS, buildJsonObject {
            put(CHANNEL, settings.channel)
            put(AUTO_CHECK, settings.autoCheck)
        })
    }

    fun plan(current: UpdateSettings, document: JsonObject): RestorePlan {
        val member = document[UPDATE_PREFS] ?: return RestorePlan(current)
        val preferences = member as? JsonObject ?: return RestorePlan(current, unreadable = 1)
        var channel = current.channel
        var autoCheck = current.autoCheck
        var updated = 0
        var skipped = 0
        var unreadable = 0
        preferences[CHANNEL]?.let { value ->
            val incoming = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                incoming != "stable" && incoming != "beta" -> unreadable++
                incoming == channel -> skipped++
                else -> { channel = incoming; updated++ }
            }
        }
        preferences[AUTO_CHECK]?.let { value ->
            val incoming = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            when {
                incoming == null -> unreadable++
                incoming == autoCheck -> skipped++
                else -> { autoCheck = incoming; updated++ }
            }
        }
        return RestorePlan(UpdateSettings(channel, autoCheck), updated, skipped, unreadable)
    }
}
