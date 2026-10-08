package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class ContextPolicyUserCapTest {
    @Test fun capsUseProportionalThresholdsEvenBelowNativeSmallTiers() {
        for (cap in listOf(8_000, 16_000, 32_000, 64_000, 105_000)) {
            val p = ContextPolicy.forUserCap(cap)
            assertTrue(p.manualCompactAllowed)
            assertFalse(p.exhaustedOnly)
            assertEquals((cap * .70).toInt(), p.offloadThreshold)
            assertEquals((cap * .55).toInt(), p.offloadTarget)
            assertEquals((cap * .85).toInt(), p.compactThreshold)
            assertEquals(ContextPolicy.CheckResult.OK, p.check(p.compactThreshold - 1, cap))
            assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(p.compactThreshold, cap))
            assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(cap + 1, cap))
        }
    }

    @Test fun resolvesGlobalAndGroupCapsWithoutExceedingNativeWindow() {
        assertEquals(ContextPolicy.Window(8_000, true), ContextPolicy.resolveWindow(1_000_000, 105_000, 8_000))
        assertEquals(ContextPolicy.Window(105_000, true), ContextPolicy.resolveWindow(1_000_000, 105_000, 200_000))
        assertEquals(ContextPolicy.Window(16_000, false), ContextPolicy.resolveWindow(16_000, 105_000, 32_000))
        assertEquals(ContextPolicy.Window(16_000, false), ContextPolicy.resolveWindow(16_000, 16_000, -1))
        assertEquals(ContextPolicy.Window(32_000, true), ContextPolicy.resolveWindow(null, null, 32_000))
        assertNull(ContextPolicy.resolveWindow(0, null, -1))
    }

    @Test fun nativeAdvisoryExhaustionIsPreservedButHardOverflowMayCompact() {
        val p = ContextPolicy.forContextWindow(40_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(39_000, 40_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(40_000, 40_000))
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, ContextPolicy.forContextWindow(16_000).check(16_000, 16_000))
        assertEquals(ContextPolicy.CheckResult.OK, ContextPolicy.forContextWindow(0).check(50_000, 0))
    }

    private fun step(measured: Int, raw: Int = measured, canCompact: Boolean = false,
                     spent: Boolean = false, userCap: Boolean = false) = ContextPolicy.inLoopStep(
        ContextPolicy.CheckResult.NEEDS_COMPACT, measured, raw, 32_000, canCompact, 1.4, spent, userCap,
    )

    @Test fun failedOrSpentCompactionCanSendAboveAdvisoryLineWithinWindow() {
        assertEquals(ContextPolicy.InLoopStep.COMPACT, step(28_000, canCompact = true))
        assertEquals(ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW, step(28_000))
        assertEquals(ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW, step(31_999, userCap = true))
        assertEquals(ContextPolicy.InLoopStep.STOP, step(32_000, userCap = true))
        assertEquals(ContextPolicy.InLoopStep.STOP, step(33_000, 33_000))
    }

    @Test fun probeIsOnceAndNeverBypassesUserCap() {
        assertEquals(ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE, step(34_000, 26_000))
        assertEquals(ContextPolicy.InLoopStep.STOP, step(34_000, 26_000, spent = true))
        assertEquals(ContextPolicy.InLoopStep.STOP, step(34_000, 26_000, userCap = true))
        assertEquals(ContextPolicy.InLoopStep.PROCEED, ContextPolicy.inLoopStep(
            ContextPolicy.CheckResult.EXHAUSTED, 999, 999, 0, false, 1.0, true,
        ))
    }
}
