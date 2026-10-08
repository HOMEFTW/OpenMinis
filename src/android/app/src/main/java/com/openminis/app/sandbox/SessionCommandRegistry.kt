package com.openminis.app.sandbox

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.util.concurrent.ConcurrentHashMap

/** Register before admission so Stop also cancels work still in the queue. */
internal class SessionCommandRegistry {
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    suspend fun <T> run(sessionId: String, block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        jobs.compute(sessionId) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).also { it.add(job) } }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            jobs.computeIfPresent(sessionId) { _, set -> set.remove(job); set.takeIf { it.isNotEmpty() } }
        }
    }

    fun stop(sessionId: String? = null) {
        val targets = if (sessionId == null) ArrayList(jobs.keys) else listOf(sessionId)
        targets.forEach { id -> jobs[id]?.let { ArrayList(it) }?.forEach { it.cancel() } }
    }

    internal val activeCount: Int get() = jobs.values.sumOf { it.size }
}
