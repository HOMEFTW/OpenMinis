package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextTokenCalibrationTest {
    @Test
    fun `reported input calibrates both retained history and new content`() {
        val calibration = ContextTokenCalibration()
        calibration.record("entry:model", 30_000, 90_000)
        assertEquals(96_000, calibration.estimate("entry:model", 32_000))
        assertEquals(4_000, ChatViewModel.maxOutputTokensFor(100_000, 16_384,
            calibration.estimate("entry:model", 32_000)))
    }

    @Test
    fun `compacted payload does not retain old absolute usage`() {
        val calibration = ContextTokenCalibration()
        calibration.record("entry:model", 30_000, 90_000)
        assertEquals(9_000, calibration.estimate("entry:model", 3_000))
    }

    @Test
    fun `fallback model and provider do not inherit another tokenizer calibration`() {
        val calibration = ContextTokenCalibration()
        calibration.record("entry:model", 30_000, 90_000)
        assertEquals(32_000, calibration.estimate("entry:other", 32_000))
        assertEquals(32_000, calibration.estimate("other:model", 32_000))
    }

    @Test
    fun `usage updates use raw estimate without compounding correction`() {
        val calibration = ContextTokenCalibration()
        repeat(3) { calibration.record("entry:model", 30_000, 90_000) }
        assertEquals(90_000, calibration.estimate("entry:model", 30_000))
        calibration.record("entry:model", 30_000, 0)
        assertEquals(90_000, calibration.estimate("entry:model", 30_000))
    }

    @Test
    fun `calibration never lowers local estimate or overflows`() {
        val calibration = ContextTokenCalibration()
        calibration.record("entry:model", 30_000, 10_000)
        assertEquals(32_000, calibration.estimate("entry:model", 32_000))
        calibration.record("entry:model", 1, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, calibration.estimate("entry:model", Int.MAX_VALUE))
    }
}
