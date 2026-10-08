package com.openminis.app.agent.jobs

import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes the initial tool-result insert with a child's final-result rewrite. */
internal class DelegateResultLedger {
    private val lock = Mutex()
    private val finals = ConcurrentHashMap<String, AgentContentPart.ToolResult>()
    private val persisted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var closed = false

    private fun receipt(id: String) = persisted.getOrPut(id) { CompletableDeferred() }

    fun apply(parts: List<AgentContentPart>): List<AgentContentPart> = parts.map {
        if (it is AgentContentPart.ToolResult) finals[it.id] ?: it else it
    }

    suspend fun finish(result: AgentContentPart.ToolResult, rewrite: suspend (AgentContentPart.ToolResult) -> Boolean) {
        val ready = lock.withLock {
            if (closed) throw CancellationException("Parent view model was cleared")
            finals[result.id] = result
            receipt(result.id).also { if (rewrite(result)) it.complete(Unit) }
        }
        // A very fast child may finish before the parent's initial insert.
        // persist() inserts the final payload and acknowledges it before callback delivery.
        ready.await()
    }

    suspend fun rewriteCurrent(result: AgentContentPart.ToolResult, rewrite: suspend (AgentContentPart.ToolResult) -> Boolean) {
        lock.withLock {
            if (rewrite(finals[result.id] ?: result)) receipt(result.id).complete(Unit)
        }
    }

    suspend fun <T> persist(results: List<AgentContentPart.ToolResult>, write: suspend (List<AgentContentPart.ToolResult>) -> T): T =
        lock.withLock {
            val saved = write(results.map { finals[it.id] ?: it })
            results.forEach { receipt(it.id).complete(Unit) }
            saved
        }

    fun forget(id: String) { finals.remove(id); persisted.remove(id) }

    fun close() { closed = true; persisted.values.forEach { it.cancel() } }
}
