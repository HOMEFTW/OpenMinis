package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlacementTest {
    @Test fun `portrait drag cannot enter status bar or home gesture region`() {
        val area = OverlayPlacement.SafeArea(1080, 2400, top = 90, bottom = 80, padding = 30)
        assertEquals(OverlayPlacement.Point(30, 120), area.clamp(OverlayPlacement.Point(-1000, -1000), 540, 140))
        assertEquals(OverlayPlacement.Point(510, 2150), area.clamp(OverlayPlacement.Point(5000, 5000), 540, 140))
    }

    @Test fun `landscape safe area includes side cutout and back gesture edges`() {
        val area = OverlayPlacement.SafeArea(2400, 1080, left = 130, top = 60, right = 90, bottom = 50, padding = 20)
        val point = area.clamp(OverlayPlacement.Point(0, 0), 660, 130)
        assertEquals(OverlayPlacement.Point(150, 80), point)
        val bottom = area.initial(660, 130)
        assertEquals(OverlayPlacement.Point(150, 880), bottom)
    }

    @Test fun `saved position is clamped after display size changes`() {
        val portrait = OverlayPlacement.SafeArea(1080, 2400, top = 90, bottom = 80)
        val landscape = OverlayPlacement.SafeArea(2400, 1080, left = 100, top = 60, right = 100, bottom = 50)
        val old = OverlayPlacement.Point(500, 2000)
        assertEquals(old, portrait.clamp(old, 500, 130))
        assertEquals(OverlayPlacement.Point(500, 900), landscape.clamp(old, 660, 130))
        assertEquals(old, portrait.clamp(old, 500, 130))
    }

    @Test fun `tiny usable region still produces a valid coordinate range`() {
        val area = OverlayPlacement.SafeArea(100, 100, left = 40, top = 40, right = 40, bottom = 40, padding = 20)
        assertTrue(area.availableWidth > 0)
        assertTrue(area.availableHeight > 0)
        val point = area.clamp(OverlayPlacement.Point(Int.MAX_VALUE, Int.MIN_VALUE), area.availableWidth, area.availableHeight)
        assertTrue(point.x in 0..99)
        assertTrue(point.y in 0..99)
    }
}
