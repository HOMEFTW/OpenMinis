package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun maintenanceReleaseDoesNotShadowAnEarlierMajorRelease() {
        assertEquals("2.0", UpdateChecker.highestVersion(listOf("1.17", "2.0", "1.20")) { it })
    }

    @Test fun numericVersionsAndApkCandidatesAreComparedSeparately() {
        val releases = listOf("1.9" to true, "2.0" to false, "1.20" to true)
        assertEquals("2.0", UpdateChecker.highestVersion(releases) { it.first }?.first)
        assertEquals("1.20", UpdateChecker.highestVersion(releases.filter { it.second }) { it.first }?.first)
        assertNull(UpdateChecker.highestVersion(emptyList<String>()) { it })
    }
}
