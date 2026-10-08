package com.openminis.app.ui.chat

import com.openminis.app.scheduled.ScheduledTaskMarker
import kotlinx.coroutines.Job

/** Each local invocation keeps its own run id, cancellation and reply. */
internal object QueuedPromptBatching {
    /** Await one inserted invocation without promoting its cancellation to the parent. */
    suspend fun runIsolated(onReady: (Job) -> Unit, block: suspend () -> Unit): Boolean =
        QueuedRunIsolation.run(onReady, block)
    const val RESUME_NUDGE = "<system-reminder>The inserted scheduled task has finished. Resume the task you were working on before it fired; if that task is already complete, say so briefly.</system-reminder>"

    fun scheduledTaskId(prompt: QueuedPrompt, isUser: (QueuedPrompt) -> Boolean): String? =
        if (isUser(prompt)) null else ScheduledTaskMarker.parse(prompt.text)?.taskId

    /** Ordinary programmatic mail waits for convergence; scheduled fires may use a tool gap. */
    fun nextInsertBatch(queue: List<QueuedPrompt>, isUser: (QueuedPrompt) -> Boolean): List<QueuedPrompt> =
        listOfNotNull(queue.firstOrNull { isUser(it) || scheduledTaskId(it, isUser) != null })

    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> = listOfNotNull(queue.firstOrNull())

    fun supersededBy(queue: List<QueuedPrompt>, taskId: String, isUser: (QueuedPrompt) -> Boolean): List<String> =
        queue.filter { scheduledTaskId(it, isUser) == taskId }.map { it.id }

    fun insertedEnvelope(text: String): String =
        ScheduledTaskMarker.parse(text)?.copy(insertedMidTask = true)?.xml ?: text
}
