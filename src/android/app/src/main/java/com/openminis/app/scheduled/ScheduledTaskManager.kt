package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger

/**
 * [T-android-scheduled-tasks-design] Schedules / cancels AlarmManager
 * entries for [ScheduledTask] rows held in [ScheduledTaskStore]. Pairs with
 * [ScheduledTaskAlarmReceiver] which fires on the trigger and hands the
 * task off to [ScheduledAgentRunner].
 *
 * Repeating tasks (DAILY / WEEKDAYS / CUSTOM) are NOT scheduled via
 * AlarmManager.setRepeating — that primitive is inexact on Android 19+
 * and Doze makes it worse. Instead the receiver re-schedules the next
 * occurrence after every fire, giving Doze-tolerant precision.
 */
class ScheduledTaskManager(private val context: Context) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val store = ScheduledTaskStore(context)

    init { ensureNotificationChannel() }

    /** Read-only access to persistence for callers that only need data. */
    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        val armed = if (task.usesAnchor && task.anchorMs == null) {
            task.copy(anchorMs = System.currentTimeMillis())
        } else task
        store.upsert(armed)
        if (armed.triggerKind == ScheduledTriggerKind.ON_COMPLETION) ScheduledCompletionTriggers.ensureListening(context)
        if (armed.enabled) registerAlarm(armed)
        return armed
    }

    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        val merged = store.update(task.id) { previous ->
            val incoming = mergeBookkeeping(task, previous)
            if (incoming.enabled && !previous.enabled && incoming.usesAnchor) {
                incoming.copy(anchorMs = System.currentTimeMillis(), triggeredCount = 0)
            } else if (incoming.enabled && !previous.enabled && incoming.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
                incoming.copy(triggeredCount = 0)
            } else incoming
        }
            ?: create(task)
        if (merged.enabled) registerAlarm(merged)
        return merged
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val updated = store.update(taskId) { t ->
            when {
                enabled && !t.enabled && t.usesAnchor ->
                    t.copy(enabled = true, anchorMs = System.currentTimeMillis(), triggeredCount = 0)
                enabled && !t.enabled && t.triggerKind == ScheduledTriggerKind.ON_COMPLETION ->
                    t.copy(enabled = true, triggeredCount = 0)
                else -> t.copy(enabled = enabled)
            }
        } ?: return
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
        if (enabled && updated.triggerKind == ScheduledTriggerKind.ON_COMPLETION) ScheduledCompletionTriggers.ensureListening(context)
    }

    fun claimOneShotFire(taskId: String): ScheduledTask? = store.update(taskId) { t ->
        if (!t.enabled || t.triggerKind != ScheduledTriggerKind.ON_COMPLETION || (t.triggeredCount ?: 0) >= 1) null
        else t.copy(enabled = false, triggeredCount = 1)
    }

    fun delete(taskId: String) {
        cancelAlarm(taskId)
        store.delete(taskId)
    }

    /** Disable future fires while retaining each task's execution history. */
    fun disableAllCreatedBy(sessionIds: Set<String>) {
        store.stopCreatedBy(sessionIds).forEach { cancelAlarm(it.id) }
    }

    /** Both the pending slot identity and the running guard are checked under the store lock. */
    fun claimRunNow(
        taskId: String,
        now: Long = System.currentTimeMillis(),
        expected: ScheduledTask? = null,
        isRunning: (String) -> Boolean = ScheduledSessionCoordinator::isTaskRunning,
    ): ScheduledTask? {
        val claimed = store.update(taskId) { current ->
            if (isRunning(current.id)) return@update null
            if (expected != null && !samePendingRun(current, expected, now)) return@update null
            runNowTransition(current, now)
        } ?: return null
        if (claimed.enabled) registerAlarm(claimed, now) else cancelAlarm(taskId)
        return claimed
    }

    /**
     * Re-register every enabled task with AlarmManager. Called from
     * [com.openminis.app.offload.AlarmReceiver]'s BOOT_COMPLETED branch
     * so persisted tasks survive a device reboot.
     */
    fun rescheduleAll() {
        ScheduledAgentRunner.recoverPending(context)
        val tasks = store.all()
        if (tasks.any { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION }) ScheduledCompletionTriggers.ensureListening(context)
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    /**
     * Called by the receiver after firing — for repeating tasks, schedule
     * the next occurrence. ONCE tasks get disabled in-place (enabled=false)
     * so they linger in the list with their lastResult* metadata visible.
     */
    fun rescheduleNext(taskId: String): Boolean {
        val now = System.currentTimeMillis()
        val claimed = store.update(taskId) { t ->
            if (!t.enabled || t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) return@update null
            if (!t.isCalendar) {
                if (!relativeAlarmDue(t, now)) return@update null
                afterAlarmFire(t, now)?.first
            } else if (t.repeatMode == ScheduledRepeatMode.ONCE) {
                if (calendarSlotTakenByRunNow(t, now)) return@update null
                t.copy(enabled = false, triggeredCount = 1)
            } else {
                if (calendarSlotTakenByRunNow(t, now)) return@update null
                // Repeated deliveries within the same slot must not launch two runs.
                if (t.anchorMs?.let { now - it in 0 until 60_000 } == true) return@update null
                t.copy(anchorMs = now, triggeredCount = (t.triggeredCount ?: 0) + 1)
            }
        }
        val current = claimed ?: store.get(taskId)
        if (current?.enabled == true) registerAlarm(current, now + 1_000)
        return claimed != null
    }

    fun markFired(
        taskId: String,
        sessionId: String?,
        resultPreview: String?,
        ok: Boolean = true,
        executionId: String? = null,
        firedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        return store.update(taskId) { withRunResult(it, sessionId, resultPreview, ok, executionId, firedAt) } != null
    }

    private fun registerAlarm(task: ScheduledTask, now: Long = System.currentTimeMillis()) {
        if (task.triggerKind == ScheduledTriggerKind.ON_COMPLETION) return
        val triggerAt = task.nextTriggerMs(now) ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id)
        try {
            val alarms = alarmManager ?: run {
                AppLogger.error(TAG, "AlarmManager unavailable for task=${task.id}")
                return
            }
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager?.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Posts when a scheduled task finishes running."
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"
        private const val ALARM_EARLY_SLACK_MS = 5_000L

        /** Completion time never replaces the envelope's original fire time. */
        internal fun withRunResult(task: ScheduledTask, sessionId: String?, preview: String?, ok: Boolean,
            executionId: String?, firedAt: Long): ScheduledTask? {
            if (executionId != null && task.runHistory.any { it.executionId == executionId }) return null
            val history = (listOf(ScheduledRun(firedAt, sessionId, preview, ok, executionId)) + task.runHistory)
                .sortedByDescending { it.firedAt }.take(ScheduledTask.MAX_RUN_HISTORY)
            val latest = history.first()
            return task.copy(lastFiredAt = latest.firedAt, lastResultPreview = latest.preview,
                lastResultSessionId = latest.sessionId, runHistory = history, fireCount = task.firesSoFar + 1)
        }

        internal fun pendingRunKey(task: ScheduledTask, now: Long): String? {
            if (!task.enabled) return null
            return when (task.triggerKind) {
                ScheduledTriggerKind.CALENDAR -> task.nextTriggerMs(now)?.let { "slot:$it" }
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL ->
                    task.nextTriggerMs(now)?.let { "run:${task.triggeredCount ?: 0}" }
                ScheduledTriggerKind.ON_COMPLETION -> if ((task.triggeredCount ?: 0) == 0) "completion:0" else null
            }
        }

        internal fun samePendingRun(current: ScheduledTask, expected: ScheduledTask, now: Long): Boolean =
            current.enabled == expected.enabled && current.triggerKind == expected.triggerKind &&
                current.stopGeneration == expected.stopGeneration &&
                current.anchorMs == expected.anchorMs && current.triggeredCount == expected.triggeredCount &&
                current.runNowSlotMs == expected.runNowSlotMs && pendingRunKey(current, now) == pendingRunKey(expected, now)

        internal fun runNowTransition(task: ScheduledTask, now: Long): ScheduledTask? {
            if (!task.enabled) return null
            return when (task.triggerKind) {
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL ->
                    if (task.nextTriggerMs(now) == null) null else afterAlarmFire(task, now)?.first
                ScheduledTriggerKind.CALENDAR -> task.nextTriggerMs(now)?.let { slot ->
                    task.copy(runNowSlotMs = slot, enabled = task.repeatMode != ScheduledRepeatMode.ONCE)
                }
                // Preserve the fork CLI's ability to run an event task immediately,
                // consuming the same one-shot claim its upstream completion would take.
                ScheduledTriggerKind.ON_COMPLETION -> if ((task.triggeredCount ?: 0) == 0)
                    task.copy(enabled = false, triggeredCount = 1) else null
            }
        }

        internal fun calendarSlotTakenByRunNow(task: ScheduledTask, now: Long): Boolean =
            task.runNowSlotMs?.let { now >= it - ALARM_EARLY_SLACK_MS && now <= it + 30 * 60_000L } == true

        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER -> t.delaySec?.let {
                    it > 0 && (t.triggeredCount ?: 0) < 1 && anchor + it * 1000 <= now + ALARM_EARLY_SLACK_MS
                } == true
                ScheduledTriggerKind.INTERVAL -> t.intervalSec?.let {
                    it >= 60 && t.remainingFires != 0 && anchor + it * 1000 <= now + ALARM_EARLY_SLACK_MS
                } == true
                ScheduledTriggerKind.CALENDAR, ScheduledTriggerKind.ON_COMPLETION -> false
            }
        }

        internal fun afterAlarmFire(t: ScheduledTask, now: Long): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val next = t.copy(triggeredCount = (t.triggeredCount ?: 0) + 1, anchorMs = now)
                if (next.remainingFires == 0) next.copy(enabled = false) to false else next to true
            }
            ScheduledTriggerKind.CALENDAR, ScheduledTriggerKind.ON_COMPLETION -> null
        }

        internal fun mergeBookkeeping(incoming: ScheduledTask, previous: ScheduledTask): ScheduledTask = incoming.copy(
            runHistory = previous.runHistory,
            fireCount = previous.fireCount,
            anchorMs = previous.anchorMs,
            triggeredCount = previous.triggeredCount,
            lastFiredAt = previous.lastFiredAt,
            lastResultPreview = previous.lastResultPreview,
            lastResultSessionId = previous.lastResultSessionId,
            creatorSessionId = previous.creatorSessionId ?: incoming.creatorSessionId,
            runNowSlotMs = previous.runNowSlotMs,
            stopGeneration = previous.stopGeneration,
        )
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"
    }
}
