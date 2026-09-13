package com.openminis.app.data

import android.content.Context

internal object TaskBudgetPrefs {
    val options = listOf(10, 25, 50, 100, 200)
    const val DEFAULT = 200
    fun validated(value: Int): Int = value.takeIf { it in options } ?: DEFAULT
    fun limit(context: Context): Int = validated(context.getSharedPreferences("task_budget", Context.MODE_PRIVATE).getInt("rounds", DEFAULT))
    fun save(context: Context, value: Int) {
        context.getSharedPreferences("task_budget", Context.MODE_PRIVATE).edit().putInt("rounds", validated(value)).apply()
    }
}

data class TaskBudgetProgress(val round: Int = 0, val limit: Int = 200) {
    val nearLimit: Boolean get() = round > 0 && round.toLong() * 5 >= limit.toLong() * 4
}
