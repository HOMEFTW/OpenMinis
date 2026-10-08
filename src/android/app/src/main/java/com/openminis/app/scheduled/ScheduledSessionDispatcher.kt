package com.openminis.app.scheduled

import com.openminis.app.MinisApp
import com.openminis.app.agent.jobs.*
import com.openminis.app.sandbox.SessionWorkspaceRegistry
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Admission uses the same cached VM and run states as the local headless runner. */
internal object ScheduledSessionDispatcher {
    private val admissionLocks = ConcurrentHashMap<String, Mutex>()
    suspend fun dispatch(
        app: MinisApp,
        delivery: ScheduledDelivery,
        queue: ScheduledDeliveryQueue,
        timeoutMs: Long,
    ): HeadlessChatRunner.PromptResult? {
        var childJob: AgentJob? = null
        var admittedRun: HeadlessChatRunner.SessionMailRun? = null
        try {
        val run = admissionLocks.getOrPut(delivery.sessionId) { Mutex() }.withLock {
        ChatViewModelStore.holdingForSend(delivery.sessionId) { withContext(Dispatchers.Main) {
            val pool = if (delivery.task.targetMode is ScheduledTargetMode.ChildOfCurrent)
                ChatViewModelStore.PoolKind.CHILD else ChatViewModelStore.PoolKind.NORMAL
            val vm = HeadlessChatRunner.viewModelFor(app, delivery.sessionId, pool)
            fun canAccept(): Boolean = if (delivery.task.targetMode is ScheduledTargetMode.RerunMessage)
                vm.canAcceptSessionMail() else vm.canAcceptScheduledMail()
            if (!canAccept()) return@withContext null
            val childMode = delivery.task.targetMode as? ScheduledTargetMode.ChildOfCurrent
            if (childMode != null && !AgentJobRegistry.canStartChildJob) return@withContext null
            val ready = withTimeoutOrNull(5_000) { vm.activeEntryId.first { it != null } }
            if (!canAccept()) return@withContext null
            val claimed = withContext(Dispatchers.IO) { queue.claim(delivery.executionId) }
                ?: return@withContext null
            if (!canAccept()) {
                withContext(Dispatchers.IO) { queue.defer(delivery.executionId) }
                return@withContext null
            }
            val runId = vm.allocateRunId()
            if (childMode != null) {
                val job = AgentJobRegistry.tryRegisterChild(
                    title = claimed.task.label.ifBlank { "Scheduled task" },
                    origin = AgentJobOrigin.SCHEDULED,
                    target = AgentJobTarget.ChildOfCurrent(childMode.sessionId, null),
                    prompt = claimed.task.prompt,
                    then = AgentJobThen.FollowUpParent(null),
                    runSessionId = delivery.sessionId,
                )
                if (job == null) {
                    withContext(Dispatchers.IO) { queue.defer(delivery.executionId) }
                    return@withContext null
                }
                childJob = job
                val root = SessionWorkspaceRegistry.restore(childMode.sessionId) {
                    app.chatRepository.getSession(it)?.parentSessionId
                }
                SessionWorkspaceRegistry.register(delivery.sessionId, root)
                vm.helperConfig = HelperConfig(childMode.sessionId, "", job.id, 25, job.title, HelperModelTier.PRIMARY)
                HeadlessChatRunner.existingViewModel(childMode.sessionId)?.let { parent ->
                    vm.adoptBrowserTabPool(parent.browserTabPool)
                }
                AgentJobRegistry.registerTabRelease(job.id) { vm.browserTabPool.releaseTabs(delivery.sessionId) }
                if (!AgentJobRegistry.markRunning(job.id, delivery.sessionId) { vm.cancelRun(runId) }) {
                    vm.rejectRun(runId, "Cancelled")
                    return@withContext HeadlessChatRunner.SessionMailRun(vm, runId)
                }
            }
            admittedRun = HeadlessChatRunner.SessionMailRun(vm, runId)
            if (ready == null) {
                vm.rejectRun(runId, "no_provider_resolved_in_5s")
            } else {
                val mode = claimed.task.targetMode
                if (mode is ScheduledTargetMode.RerunMessage) {
                    vm.startScheduledRetryForRun(mode.messageId, runId, claimed.task.thinkingLevel)
                } else {
                    val prefill = listOfNotNull(claimed.task.prefillToolCall)
                    val marked = ScheduledTaskMarker(
                        taskId = claimed.task.id, label = claimed.task.label,
                        nextFireAtMs = ScheduledTaskManager(app).get(claimed.task.id)?.nextTriggerMs(),
                        prompt = claimed.task.prompt, prefilledTool = prefill.firstOrNull()?.toolName,
                        firedAtMs = claimed.firedAt,
                    ).xml
                    vm.startScheduledForRun(marked, runId, claimed.task.thinkingLevel, prefill = prefill)
                }
            }
            HeadlessChatRunner.SessionMailRun(vm, runId)
        } } } ?: return null
        val result = withTimeoutOrNull(timeoutMs) {
            HeadlessChatRunner.awaitSessionMail(app, delivery.sessionId, run)
        } ?: HeadlessChatRunner.PromptResult(
            status = "Timeout", responseText = run.viewModel.assistantTextForRun(run.runId),
            timedOut = true, runId = run.runId,
        )
        withContext(Dispatchers.Main) {
            childJob?.let { job ->
                val state = when {
                    result.timedOut -> AgentJobState.TIMEOUT
                    ScheduledRunPolicy.isSuccess(result.status) -> AgentJobState.DONE
                    result.status == "Cancelled" -> AgentJobState.CANCELLED
                    else -> AgentJobState.FAILED
                }
                AgentJobRegistry.finish(job.id, state, result.responseText)
            }
            if (result.timedOut) run.viewModel.cancelRun(run.runId)
        }
        return result
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                childJob?.let { job ->
                    AgentJobRegistry.finish(job.id,
                        if (error is kotlinx.coroutines.CancellationException) AgentJobState.CANCELLED else AgentJobState.FAILED,
                        error.message)
                }
                admittedRun?.let { it.viewModel.cancelRun(it.runId) }
            }
            throw error
        }
    }
}
