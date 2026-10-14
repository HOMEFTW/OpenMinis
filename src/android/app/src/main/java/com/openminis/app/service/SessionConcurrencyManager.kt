package com.openminis.app.service

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.resume

/** Limits concurrent sessions; queued sessions are admitted in FIFO order. */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 6
    private const val SLOT_WAIT_WARN_MS = 10_000L

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()
    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private class Waiter(val sessionId: String, val continuation: CancellableContinuation<Unit>)
    private val holders = mutableMapOf<String, Waiter>()
    private val waitQueue = LinkedList<Waiter>()

    suspend fun acquireSlot(sessionId: String) {
        var watchdog: Job? = null
        try {
            // Fast admission also needs prompt-cancellation protection: a resumed
            // continuation may be cancelled before its caller can own the slot.
            suspendCancellableCoroutine<Unit> { cont ->
                val waiter = Waiter(sessionId, cont)
                cont.invokeOnCancellation {
                    synchronized(this) {
                        waitQueue.remove(waiter)
                        if (holders[sessionId] === waiter) {
                            holders.remove(sessionId)
                            _runningSessions.value = holders.keys.toSet()
                        }
                        admitQueuedLocked()
                    }
                }
                synchronized(this) {
                    if (!cont.isActive) return@synchronized
                    if (holders.size < MAX_CONCURRENT && waitQueue.isEmpty() && sessionId !in holders) {
                        grantLocked(waiter)
                    } else {
                        waitQueue.add(waiter)
                        _suspendedSessions.value = waitQueue.map { it.sessionId }
                        val started = android.os.SystemClock.elapsedRealtime()
                        println("[T-STALL-DIAG] slot WAIT-BEGIN sid=$sessionId ${diagSnapshot()}")
                        watchdog = CoroutineScope(Dispatchers.IO).launch {
                            while (true) {
                                delay(SLOT_WAIT_WARN_MS)
                                println("[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId " +
                                    "waitedMs=${android.os.SystemClock.elapsedRealtime() - started} ${diagSnapshot()}")
                            }
                        }
                    }
                }
            }
        } finally {
            watchdog?.cancel()
        }
    }

    /** Caller holds this object's monitor for all queue/holder transitions. */
    private fun grantLocked(waiter: Waiter) {
        holders[waiter.sessionId] = waiter
        _runningSessions.value = holders.keys.toSet()
        println("[T-STALL-DIAG] slot ACQUIRED sid=${waiter.sessionId} ${diagSnapshot()}")
        waiter.continuation.resume(Unit)
    }

    private fun admitQueuedLocked() {
        while (holders.size < MAX_CONCURRENT) {
            val next = waitQueue.firstOrNull {
                it.continuation.isActive && it.sessionId !in holders
            } ?: break
            waitQueue.remove(next)
            grantLocked(next)
        }
        _suspendedSessions.value = waitQueue.map { it.sessionId }
    }

    @Synchronized
    fun releaseSlot(sessionId: String) {
        if (holders.remove(sessionId) == null) return
        _runningSessions.value = holders.keys.toSet()
        println("[T-STALL-DIAG] slot RELEASED sid=$sessionId ${diagSnapshot()}")
        admitQueuedLocked()
    }

    @Synchronized
    fun diagSnapshot(): String =
        "running=${holders.size}/$MAX_CONCURRENT holders=${holders.keys.joinToString(",")} " +
            "suspended=${waitQueue.joinToString(",") { it.sessionId }}"

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
