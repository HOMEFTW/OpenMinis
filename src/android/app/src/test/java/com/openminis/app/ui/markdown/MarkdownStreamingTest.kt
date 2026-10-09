package com.openminis.app.ui.markdown

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownStreamingTest {
    @Test
    fun `token updates cannot reset the throttle and only the latest value is published`() = runTest {
        val source = MutableStateFlow("first")
        val published = mutableListOf<String>()
        val job = launch { source.throttleMarkdownUpdates().collect { published.add(it) } }
        runCurrent()
        repeat(10) {
            advanceTimeBy(10)
            source.value = "token-$it"
            runCurrent()
        }
        assertEquals(listOf("first"), published)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("first", "token-9"), published)
        job.cancel()
    }

    @Test
    fun `cancelled stream collector does not publish a stale pending value`() = runTest {
        val source = MutableStateFlow("first")
        val published = mutableListOf<String>()
        val job = launch { source.throttleMarkdownUpdates().collect { published.add(it) } }
        runCurrent()
        source.value = "pending"
        runCurrent()
        job.cancel()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("first"), published)
    }

    @Test
    fun `adaptive delay retains the existing tiers`() {
        assertEquals(listOf(200L, 300L, 500L, 1_000L, 1_500L, 2_000L),
            listOf(499, 500, 2_000, 32_000, 64_000, 128_000).map(::markdownThrottleMillis))
    }
}
