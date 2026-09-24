package com.openminis.app.provider

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

@OptIn(ExperimentalCoroutinesApi::class)
class SseProgressWatchdogTest {
    @Test
    fun `idle timeout is raised without real progress`() = runTest {
        val timeout = AtomicReference<SseIdleTimeoutException?>()
        val callbackCount = AtomicInteger()
        val watchdog = SseProgressWatchdog(
            idleTimeoutMs = 3_000L,
            pollIntervalMs = 100L,
            nowNanos = { testScheduler.currentTime * 1_000_000L },
            onTimeout = {
                timeout.set(it)
                callbackCount.incrementAndGet()
            },
        )
        val job = watchdog.start(this)

        advanceTimeBy(2_999L)
        runCurrent()
        assertNull(timeout.get())

        advanceTimeBy(1L)
        runCurrent()

        val error = timeout.get()
        assertTrue(error is InterruptedIOException)
        assertEquals(3_000L, error?.idleTimeoutMs)
        assertTrue(error?.message.orEmpty().contains("no text/thinking/tool/completion progress"))
        assertEquals(1, callbackCount.get())
        job.cancel()
    }

    @Test
    fun `actual progress resets deadline while heartbeat does not`() = runTest {
        val timeout = AtomicReference<SseIdleTimeoutException?>()
        val watchdog = SseProgressWatchdog(
            idleTimeoutMs = 3_000L,
            pollIntervalMs = 100L,
            nowNanos = { testScheduler.currentTime * 1_000_000L },
            onTimeout = { timeout.set(it) },
        )
        val job = watchdog.start(this)

        // SSE heartbeat/usage/start events are intentionally not markProgress calls.
        advanceTimeBy(2_500L)
        runCurrent()
        assertNull(timeout.get())

        watchdog.markProgress()
        advanceTimeBy(2_999L)
        runCurrent()
        assertNull(timeout.get())

        advanceTimeBy(1L)
        runCurrent()
        assertFalse(timeout.get() == null)
        job.cancel()
    }

    @Test
    fun `call cancellation runs while parent job is cancelling`() = runBlocking {
        val parent = Job()
        val cancellationCount = AtomicInteger()
        val callCancellation = CoroutineScope(parent).cancelOnCancellation {
            cancellationCount.incrementAndGet()
        }

        parent.cancel()
        callCancellation.join()

        assertEquals(1, cancellationCount.get())
        callCancellation.cancel()
    }

    @Test
    fun `cancellation closes a call while its blocking operation is active`() = runBlocking {
        class BlockingCall {
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            private val release = CountDownLatch(1)
            var cancelled = false

            fun executeBlocking() {
                started.countDown()
                try {
                    release.await()
                } finally {
                    finished.countDown()
                }
            }

            fun cancel() {
                cancelled = true
                release.countDown()
            }
        }

        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        val call = BlockingCall()
        val callCancellation = scope.cancelOnCancellation { call.cancel() }
        val blockingJob = scope.launch { call.executeBlocking() }

        assertTrue(call.started.await(1, TimeUnit.SECONDS))
        parent.cancel()
        withTimeout(1_000L) { blockingJob.join() }

        assertTrue(call.cancelled)
        assertTrue(call.finished.await(1, TimeUnit.SECONDS))
        callCancellation.cancel()
    }
}
