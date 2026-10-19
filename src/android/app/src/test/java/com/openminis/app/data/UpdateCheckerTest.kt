package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun updateEndpointsStayOnTheUsersFork() {
        assertEquals("https://api.github.com/repos/HOMEFTW/OpenMinis/releases?per_page=30", UpdateChecker.RELEASES_API_URL)
        assertEquals("https://github.com/HOMEFTW/OpenMinis/releases", UpdateChecker.RELEASES_URL)
    }

    @Test fun currentApkWinsOverBackupAndInstrumentationAssets() {
        val assets = org.json.JSONArray("""[
            {"name":"app-debug-before-fast.apk","browser_download_url":"https://example.test/old","size":1},
            {"name":"app-debug-androidTest.apk","browser_download_url":"https://example.test/test","size":2},
            {"name":"app-debug.apk","browser_download_url":"https://example.test/current","size":3}
        ]""")
        assertEquals("https://example.test/current" to 3L, UpdateChecker.findApkAsset(assets))
        assertEquals(null to 0L, UpdateChecker.findApkAsset(org.json.JSONArray("""[
            {"name":"app-debug-before-backup.apk","browser_download_url":"https://example.test/old"}
        ]""")))
    }

    @Test fun autoCheckHonorsTheSelectedChannelAndClockRollback() {
        val hour = 3_600_000L
        val last = 100 * hour
        assertFalse(UpdatePreferences.shouldCheck(last, last + 23 * hour, "stable"))
        assertTrue(UpdatePreferences.shouldCheck(last, last + 24 * hour, "stable"))
        assertFalse(UpdatePreferences.shouldCheck(last, last + 5 * hour, "beta"))
        assertTrue(UpdatePreferences.shouldCheck(last, last + 6 * hour, "beta"))
        assertTrue(UpdatePreferences.shouldCheck(0L, last, "stable"))
        assertTrue(UpdatePreferences.shouldCheck(last, last - hour, "stable"))
    }
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
