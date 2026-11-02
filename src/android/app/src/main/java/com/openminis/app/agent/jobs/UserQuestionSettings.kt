package com.openminis.app.agent.jobs

import android.content.Context
import com.openminis.app.tools.AgentToolSwitch

object UserQuestionSettings {
    const val TIMEOUT_MINUTES_KEY = "agent.tools.userAsk.timeoutMinutes"
    const val DEFAULT_TIMEOUT_MINUTES = 5
    const val MAX_TIMEOUT_MINUTES = 1440
    val TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 30, 60, 0)

    fun timeoutMinutes(context: Context): Int = context.applicationContext
        .getSharedPreferences(AgentToolSwitch.PREFS, Context.MODE_PRIVATE)
        .getInt(TIMEOUT_MINUTES_KEY, DEFAULT_TIMEOUT_MINUTES).coerceIn(0, MAX_TIMEOUT_MINUTES)

    fun setTimeoutMinutes(context: Context, minutes: Int) {
        context.applicationContext.getSharedPreferences(AgentToolSwitch.PREFS, Context.MODE_PRIVATE)
            .edit().putInt(TIMEOUT_MINUTES_KEY, minutes.coerceIn(0, MAX_TIMEOUT_MINUTES)).apply()
    }
}
