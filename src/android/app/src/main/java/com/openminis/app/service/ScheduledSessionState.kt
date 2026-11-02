package com.openminis.app.service

/** Only the scheduling owner decides which self-targeted chats are in their countdown window. */
data class ScheduledSessionWait(
    val taskId: String,
    val sessionId: String,
    val label: String,
    val nextRunWallMs: Long?,
    val runCounter: String? = null,
)

internal fun nextScheduledWait(waits: List<ScheduledSessionWait>, runningSessions: Set<String>): ScheduledSessionWait? =
    if (runningSessions.isNotEmpty()) null else waits.minByOrNull { it.nextRunWallMs ?: Long.MAX_VALUE }

internal fun scheduledWaitStatus(nextRunLabel: String, runCounter: String?): String =
    runCounter?.takeIf { it.isNotBlank() }?.let { "$nextRunLabel · $it" } ?: nextRunLabel

internal data class AgentNotificationClock(
    val showWhen: Boolean,
    val whenMs: Long,
    val usesChronometer: Boolean,
    val countDown: Boolean = false,
)

internal fun agentNotificationClock(
    phase: AgentPhase,
    runStartWallMs: Long,
    finishedWallMs: Long,
    deadlineWallMs: Long?,
    nowWallMs: Long,
): AgentNotificationClock = when {
    phase == AgentPhase.SCHEDULED_WAIT || phase == AgentPhase.WAITING ->
        if (deadlineWallMs != null && deadlineWallMs > nowWallMs) AgentNotificationClock(true, deadlineWallMs, true, true)
        else AgentNotificationClock(false, nowWallMs, false)
    !notificationShowsTimer(phase) -> AgentNotificationClock(false, nowWallMs, false)
    phase == AgentPhase.COMPLETED -> AgentNotificationClock(true, finishedWallMs, false)
    else -> AgentNotificationClock(true, runStartWallMs, true)
}
