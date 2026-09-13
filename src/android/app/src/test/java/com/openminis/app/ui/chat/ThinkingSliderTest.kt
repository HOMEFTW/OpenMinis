package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingSliderTest {
    @Test fun astraExposesOffThroughUltra() {
        assertEquals(ThinkingLevel.entries, thinkingSliderLevels(ThinkingLevel.entries.filter { it != ThinkingLevel.OFF }))
    }

    @Test fun switchingToLowerCeilingDisplaysHighestSupportedStop() {
        val levels = thinkingSliderLevels(listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.HIGH, levels[thinkingSliderIndex(ThinkingLevel.ULTRA, levels)])
        assertEquals(0, thinkingSliderIndex(ThinkingLevel.OFF, levels))
    }

    @Test fun nonContiguousCapabilitiesNeverOfferUnsupportedTiers() {
        val levels = thinkingSliderLevels(listOf(ThinkingLevel.HIGH, ThinkingLevel.LOW, ThinkingLevel.HIGH))
        assertEquals(listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH), levels)
        assertEquals(ThinkingLevel.LOW, levels[thinkingSliderIndex(ThinkingLevel.MEDIUM, levels)])
    }

    @Test fun emptyCapabilitiesHaveOneDisabledOffStop() {
        assertEquals(listOf(ThinkingLevel.OFF), thinkingSliderLevels(emptyList()))
        assertEquals(0, thinkingSliderIndex(ThinkingLevel.HIGH, thinkingSliderLevels(emptyList())))
    }
}
