package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvVarStateStoreTest {
    @Test fun absentIdStaysEnabledAndDisableSurvivesReload() {
        val dir = tempDir()
        val store = EnvVarStateStore(dir)
        assertTrue(store.isEnabled("a"))
        store.setEnabled("a", "TOKEN", false, now = 10)
        val reloaded = EnvVarStateStore(dir)
        assertFalse(reloaded.isEnabled("a"))
        assertEquals("TOKEN", reloaded.state("a")?.key)
    }

    @Test fun clockMovingBackStillAdvancesTheStamp() {
        val dir = tempDir()
        val store = EnvVarStateStore(dir)
        store.setEnabled("a", "TOKEN", false, now = 20)
        store.setEnabled("a", "TOKEN", true, now = 5)
        assertEquals(21L, store.state("a")?.enabledUpdatedAt)
        assertTrue(store.isEnabled("a"))
    }

    @Test fun pruneDropsDeletedIdsAndKeepsUnreadableFile() {
        val dir = tempDir()
        val store = EnvVarStateStore(dir)
        store.setEnabled("gone", "OLD", false)
        store.setEnabled("keep", "KEEP", false)
        store.prune(setOf("keep"))
        assertTrue(EnvVarStateStore(dir).state("gone") == null)
        assertFalse(EnvVarStateStore(dir).isEnabled("keep"))

        File(dir, EnvVarStateStore.FILE_NAME).writeText("{not json")
        val broken = EnvVarStateStore(dir)
        assertTrue(broken.unreadable)
        broken.prune(emptySet())
        assertEquals("{not json", File(dir, EnvVarStateStore.FILE_NAME).readText())
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "env-state-${System.nanoTime()}").apply { mkdirs() }
}
