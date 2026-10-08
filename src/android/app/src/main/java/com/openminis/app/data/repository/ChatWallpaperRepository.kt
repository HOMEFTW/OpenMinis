package com.openminis.app.data.repository

import android.content.SharedPreferences
import com.openminis.app.data.model.ChatWallpaperSession
import com.openminis.app.data.model.ChatWallpaperState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local wallpaper preferences; owned by a single application-scoped instance. */
class ChatWallpaperRepository(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ChatWallpaperState> = _state.asStateFlow()

    @Synchronized
    fun setDefaultTransparency(value: Int) {
        val transparency = value.coerceIn(0, 100)
        prefs.edit().putInt(KEY_DEFAULT_TRANSPARENCY, transparency).apply()
        _state.value = _state.value.copy(defaultTransparency = transparency)
    }

    @Synchronized
    fun setSessionEnabled(id: String, enabled: Boolean) {
        setSession(id, _state.value.forSession(id).copy(enabled = enabled))
    }

    @Synchronized
    fun setSessionTransparency(id: String, transparency: Int?) {
        setSession(id, _state.value.forSession(id).copy(
            transparencyOverride = transparency?.coerceIn(0, 100),
        ))
    }

    /** A stored source replaces the destination; an absent source is a no-op. */
    @Synchronized
    fun moveSession(from: String, to: String) {
        if (from == to) return
        val current = _state.value
        val session = current.sessions[from] ?: return
        val editor = prefs.edit()
            .remove(KEY_ENABLED_PREFIX + from)
            .remove(KEY_TRANSPARENCY_PREFIX + from)
        writeSession(editor, to, session).apply()
        _state.value = current.copy(sessions = current.sessions - from + (to to session))
    }

    @Synchronized
    fun removeSession(id: String) {
        prefs.edit()
            .remove(KEY_ENABLED_PREFIX + id)
            .remove(KEY_TRANSPARENCY_PREFIX + id)
            .apply()
        _state.value = _state.value.copy(sessions = _state.value.sessions - id)
    }

    private fun setSession(id: String, session: ChatWallpaperSession) {
        writeSession(prefs.edit(), id, session).apply()
        val sessions = if (session == ChatWallpaperSession()) {
            _state.value.sessions - id
        } else {
            _state.value.sessions + (id to session)
        }
        _state.value = _state.value.copy(sessions = sessions)
    }

    private fun writeSession(
        editor: SharedPreferences.Editor,
        id: String,
        session: ChatWallpaperSession,
    ): SharedPreferences.Editor {
        // Drafts have no durable chat row. Keep their preferences in memory until promotion.
        if (id.startsWith("__new__")) {
            return editor.remove(KEY_ENABLED_PREFIX + id).remove(KEY_TRANSPARENCY_PREFIX + id)
        }
        if (session.enabled) editor.remove(KEY_ENABLED_PREFIX + id)
        else editor.putBoolean(KEY_ENABLED_PREFIX + id, false)
        if (session.transparencyOverride == null) editor.remove(KEY_TRANSPARENCY_PREFIX + id)
        else editor.putInt(KEY_TRANSPARENCY_PREFIX + id, session.transparencyOverride)
        return editor
    }

    private fun readState(): ChatWallpaperState {
        val values = prefs.all
        val sessions = mutableMapOf<String, ChatWallpaperSession>()
        for ((key, value) in values) {
            when {
                key.startsWith(KEY_ENABLED_PREFIX) && value is Boolean -> {
                    val id = key.removePrefix(KEY_ENABLED_PREFIX)
                    sessions[id] = (sessions[id] ?: ChatWallpaperSession()).copy(enabled = value)
                }
                key.startsWith(KEY_TRANSPARENCY_PREFIX) && value is Int -> {
                    val id = key.removePrefix(KEY_TRANSPARENCY_PREFIX)
                    sessions[id] = (sessions[id] ?: ChatWallpaperSession()).copy(
                        transparencyOverride = value.coerceIn(0, 100),
                    )
                }
            }
        }
        return ChatWallpaperState(
            defaultTransparency = (values[KEY_DEFAULT_TRANSPARENCY] as? Int ?: 75).coerceIn(0, 100),
            sessions = sessions.filterValues { it != ChatWallpaperSession() },
        )
    }

    companion object {
        private const val KEY_DEFAULT_TRANSPARENCY = "chat_wallpaper_default_transparency"
        private const val KEY_ENABLED_PREFIX = "chat_wallpaper_enabled:"
        private const val KEY_TRANSPARENCY_PREFIX = "chat_wallpaper_transparency:"
    }
}
