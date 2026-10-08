package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** The parent awaits the child's cleanup; only parent cancellation propagates outward. */
internal object QueuedRunIsolation {
    suspend fun run(onReady: (Job) -> Unit, block: suspend () -> Unit): Boolean = supervisorScope {
        val child = async(start = CoroutineStart.LAZY) { block() }
        onReady(child)
        try {
            child.await()
            true
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }
}
