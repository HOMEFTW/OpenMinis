package com.openminis.app.ui.navigation

import android.content.Context

internal object LaunchSessionHistory {
    fun record(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences("launch_history", Context.MODE_PRIVATE).edit()
            .putString("session", id).putLong("viewed_at", now).apply()
    }
    fun read(context: Context): Pair<String?, Long> {
        val p = context.getSharedPreferences("launch_history", Context.MODE_PRIVATE)
        return p.getString("session", null) to p.getLong("viewed_at", 0)
    }
    fun candidate(lastId: String?, viewedAt: Long, sessions: List<Pair<String, Long>>, hasDraft: (String) -> Boolean): Pair<String, Long>? {
        if (lastId != null && (sessions.any { it.first == lastId } || (lastId.startsWith("__new__") && hasDraft(lastId)))) {
            return lastId to viewedAt
        }
        return sessions.firstOrNull()
    }
    fun isRecent(viewedAt: Long, now: Long): Boolean = viewedAt > 0 && now >= viewedAt && now - viewedAt < 15 * 60_000L
}
