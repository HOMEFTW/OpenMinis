package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.agent.jobs.AgentJobRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduled-task chaining; external job owners can call [onUpstreamFinished]. */
object ScheduledCompletionTriggers {
    const val RESULT_CAP_CHARS = 8_000
    private val listening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureListening(context: Context) {
        if (!listening.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                AgentJobRegistry.completions.collect { completion ->
                    runCatching { onUpstreamFinished(app, completion.jobId, AgentJobRegistry.job(completion.jobId)?.resultText) }
                        .onFailure { AppLogger.warning("ScheduledCompletion", "job completion failed: ${it.message}") }
                }
            } finally { listening.set(false) }
        }
    }

    internal fun dependents(tasks: List<ScheduledTask>, upstreamId: String): List<ScheduledTask> = tasks.filter {
        it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION && it.onCompletionOf == upstreamId
    }

    internal fun scheduleFinished(task: ScheduledTask?, now: Long): Boolean {
        if (task == null) return false
        return when (task.triggerKind) {
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> (task.triggeredCount ?: 0) >= 1
            ScheduledTriggerKind.INTERVAL -> task.remainingFires == 0
            ScheduledTriggerKind.CALENDAR ->
                (task.repeatMode == ScheduledRepeatMode.ONCE && !task.enabled) ||
                    (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)
        }
    }

    internal fun substitute(prompt: String, result: String?): String =
        prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, result.orEmpty().take(RESULT_CAP_CHARS))

    suspend fun onScheduledRunFinished(context: Context, firedTask: ScheduledTask, result: String?) {
        val now = System.currentTimeMillis()
        // Use the fire's snapshot as well as the current row: an earlier run
        // finishing after the final alarm must not release the chain early.
        if (!scheduleFinished(firedTask, now)) return
        if (!scheduleFinished(ScheduledTaskManager(context).get(firedTask.id), now)) return
        onUpstreamFinished(context, firedTask.id, result)
    }

    suspend fun onUpstreamFinished(context: Context, upstreamId: String, result: String?) {
        val manager = ScheduledTaskManager(context)
        for (task in dependents(manager.list(), upstreamId)) {
            val claimed = manager.claimOneShotFire(task.id) ?: continue
            AppLogger.info("ScheduledCompletion", "upstream ${upstreamId.take(8)} finished -> ${task.id.take(8)}")
            ScheduledAgentRunner.run(
                context, claimed.copy(prompt = substitute(claimed.prompt, result)),
                waitForCompletion = false, scheduledFire = true,
            )
        }
    }
}
