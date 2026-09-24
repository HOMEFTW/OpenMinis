package com.openminis.app.provider

import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Raised when an SSE response stays connected but makes no usable progress. */
class SseIdleTimeoutException(
    val idleTimeoutMs: Long,
) : InterruptedIOException(
    "SSE stream made no text/thinking/tool/completion progress for " +
        "${idleTimeoutMs}ms after response headers",
)

/**
 * Small provider-shared watchdog for the part of an SSE call after response
 * headers arrive. Callers mark only actual output progress; transport
 * heartbeats, usage events and stream-start markers deliberately do not reset
 * the deadline.
 *
 * [nowNanos] is injectable so the timeout contract can be tested with virtual
 * time without waiting for the production five-minute budget.
 */
internal class SseProgressWatchdog(
    private val idleTimeoutMs: Long = DEFAULT_SSE_IDLE_TIMEOUT_MS,
    private val pollIntervalMs: Long = defaultPollIntervalMs(idleTimeoutMs),
    private val nowNanos: () -> Long = System::nanoTime,
    private val onTimeout: (SseIdleTimeoutException) -> Unit,
) {
    init {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }

    private val lastProgressNanos = AtomicLong(nowNanos())
    private val timeout = AtomicReference<SseIdleTimeoutException?>()
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)

    val timeoutException: SseIdleTimeoutException?
        get() = timeout.get()

    fun markProgress() {
        if (timeout.get() == null) lastProgressNanos.set(nowNanos())
    }

    /**
     * Starts immediately until the first delay. That matters because Provider
     * code below enters a blocking OkHttp read immediately after starting it.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        while (isActive) {
            delay(pollIntervalMs)
            if (!isActive) break

            val idleNanos = nowNanos() - lastProgressNanos.get()
            if (idleNanos >= timeoutNanos) {
                val error = SseIdleTimeoutException(idleTimeoutMs)
                if (timeout.compareAndSet(null, error)) {
                    onTimeout(error)
                }
                return@launch
            }
        }
    }

    private companion object {
        fun defaultPollIntervalMs(timeoutMs: Long): Long =
            (timeoutMs / 4).coerceIn(1L, 1_000L)
    }
}

internal const val DEFAULT_SSE_IDLE_TIMEOUT_MS = 300_000L

/**
 * Cancels one OkHttp call as soon as the surrounding flow scope starts
 * cancelling. The child is started undispatched so it is already suspended
 * in [awaitCancellation] before the caller enters blocking I/O; relying on a
 * default Job completion callback would be too late while that I/O is stuck.
 */
internal fun CoroutineScope.cancelOnCancellation(onCancel: () -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            onCancel()
        }
    }
