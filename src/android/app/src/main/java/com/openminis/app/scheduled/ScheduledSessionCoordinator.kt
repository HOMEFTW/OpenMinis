package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.ScheduledSessionWait
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Schedule holds never take a model concurrency slot or pretend an idle chat is streaming. */
object ScheduledSessionCoordinator {
    internal data class State(val waits: List<ScheduledSessionWait>, val sessions: Set<String>)
    internal data class Inputs(
        val tasks: List<ScheduledTask>, val running: Map<String, ScheduledTask>,
        val jobs: Map<String, AgentJob>, val leadMs: Long,
    )

    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handoffs = MutableStateFlow<Map<String, ScheduledTask>>(emptyMap())
    private val queued = MutableStateFlow<Map<String, ScheduledTask>>(emptyMap())
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val cancellers = ConcurrentHashMap<String, () -> Unit>()
    private var settings: BackgroundSettingsRepository? = null

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val repository = BackgroundSettingsRepository(app).also { settings = it }
        SessionActivityTracker.cancelScheduledByUser = { stopCreatedBy(app, it) }
        scope.launch {
            try {
                val inputs = combine(ScheduledTaskStore(app).observe(), handoffs, queued, AgentJobRegistry.jobs,
                    repository.scheduledCountdownLead) { tasks, starting, deliveries, jobs, lead ->
                    Inputs(tasks, starting + deliveries, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
                }
                observe(inputs).distinctUntilChanged().collect { state ->
                    SessionActivityTracker.setScheduledSessions(state.waits, state.sessions)
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AppLogger.error("ScheduledHold", "schedule observer failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Also callable from the chat's user Stop; only this creator's tasks are disabled. */
    fun stopCreatedBy(context: Context, sessionIds: Set<String>) {
        val affected = (handoffs.value + queued.value).filterValues { it.creatorSessionId in sessionIds }.keys
        cancelled.addAll(affected)
        ScheduledTaskManager(context).disableAllCreatedBy(sessionIds)
        ScheduledAgentRunner.cancelQueuedByCreators(context, sessionIds)
        affected.forEach { id -> cancellers[id]?.let { cancel -> scope.launch(Dispatchers.Main) { cancel() } } }
    }

    @Synchronized
    internal fun beginHandoff(executionId: String, task: ScheduledTask, manual: Boolean): Boolean {
        if (manual && isTaskRunning(task.id, executionId)) return false
        handoffs.value = handoffs.value + (executionId to task)
        return true
    }

    @Synchronized
    internal fun endHandoff(executionId: String) {
        handoffs.value = handoffs.value - executionId
        if (executionId !in queued.value) cancelled.remove(executionId)
    }

    @Synchronized
    internal fun setQueuedDeliveries(deliveries: List<ScheduledDelivery>) {
        queued.value = deliveries.associate { it.executionId to it.task }
        cancelled.retainAll((handoffs.value.keys + queued.value.keys).toSet())
    }

    fun isTaskRunning(taskId: String): Boolean = isTaskRunning(taskId, null)
    internal fun isTaskRunning(taskId: String, excluding: String?): Boolean =
        (handoffs.value + queued.value).any { (id, task) -> id != excluding && task.id == taskId }

    internal fun isCancelled(executionId: String): Boolean = executionId in cancelled

    internal fun registerCancellation(executionId: String, cancel: () -> Unit) {
        cancellers[executionId] = cancel
        if (isCancelled(executionId)) scope.launch(Dispatchers.Main) { cancel() }
    }

    internal fun unregisterCancellation(executionId: String) { cancellers.remove(executionId) }

    internal fun runHold(task: ScheduledTask): String? {
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        return target.sessionId.takeIf { it.isNotBlank() && it == task.creatorSessionId }
    }

    internal fun heldSession(task: ScheduledTask, now: Long, leadMs: Long, upstreamUnfinished: (String) -> Boolean): String? {
        if (!task.enabled || leadMs <= 0L) return null
        val session = runHold(task) ?: return null
        if (task.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            if (task.onCompletionOf?.let(upstreamUnfinished) != true) return null
        } else {
            val next = task.nextTriggerMs(now) ?: return null
            if (next - now > leadMs) return null
        }
        return session
    }

    internal fun derive(input: Inputs, now: Long): State {
        val tasks = input.tasks.associateBy { it.id }
        val runningIds = input.running.values.map { it.id }.toSet()
        fun unfinished(id: String): Boolean {
            if (id in runningIds) return true
            tasks[id]?.let { return it.enabled && (it.triggerKind == ScheduledTriggerKind.ON_COMPLETION || it.nextTriggerMs(now) != null) }
            return input.jobs[id]?.isActive == true
        }
        val waits = input.tasks.mapNotNull { task ->
            val session = heldSession(task, now, input.leadMs, ::unfinished) ?: return@mapNotNull null
            if (task.id in runningIds) return@mapNotNull null
            val number = if (task.triggerKind == ScheduledTriggerKind.INTERVAL) (task.triggeredCount ?: 0) + 1 else task.firesSoFar + 1
            val counter = when {
                task.isOneShot -> null
                task.triggerKind == ScheduledTriggerKind.INTERVAL && task.maxFires != null -> "$number/${task.maxFires}"
                else -> "#$number"
            }
            ScheduledSessionWait(task.id, session, task.label, task.nextTriggerMs(now), counter)
        }.sortedWith(compareBy<ScheduledSessionWait> { it.nextRunWallMs ?: Long.MAX_VALUE }.thenBy { it.taskId })
        val runningSessions = input.running.values.filter { it.id in tasks }.mapNotNull(::runHold)
        return State(waits, waits.map { it.sessionId }.toSet() + runningSessions)
    }

    internal fun nextWindowStart(tasks: List<ScheduledTask>, now: Long, leadMs: Long): Long? {
        if (leadMs <= 0L || leadMs == Long.MAX_VALUE) return null
        return tasks.asSequence().filter { it.enabled && runHold(it) != null && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION }
            .mapNotNull { it.nextTriggerMs(now) }.map { it - leadMs }.filter { it > now }.minOrNull()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observe(inputs: Flow<Inputs>, nowMs: () -> Long = System::currentTimeMillis): Flow<State> = inputs.flatMapLatest { input ->
        flow {
            while (true) {
                val now = nowMs()
                emit(derive(input, now))
                // Recompute when entering the lead window AND at a calendar slot
                // boundary, even if no preferences or task row changed.
                val nextWindow = nextWindowStart(input.tasks, now, input.leadMs)
                val nextFire = input.tasks.filter { it.enabled && runHold(it) != null }
                    .mapNotNull { it.nextTriggerMs(now) }.filter { it > now }.minOrNull()
                val next = listOfNotNull(nextWindow, nextFire).minOrNull() ?: break
                delay((next - now).coerceAtLeast(0L) + 1L)
            }
        }
    }
}
