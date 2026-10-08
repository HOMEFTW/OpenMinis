package com.openminis.app.ui.settings

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageCacheCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 10_000_000L
    private val old = now - 7_200_000L

    private fun write(path: String, bytes: Int = 10): File = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test fun clearsRegenerableCachesButPreservesLiveState() {
        write("models-cache/list.json", 30)
        val live = listOf("proot-tmp/tool", "pasted_text/draft", "share_inbound/file", "restore-extract/blob", "rclone/config")
            .map { write(it) }
        assertEquals(30L, LogsAndCachesCleaner.cachesSize(tmp.root, now))
        LogsAndCachesCleaner.clearCaches(tmp.root, now)
        assertFalse(File(tmp.root, "models-cache").exists())
        live.forEach { assertTrue(it.isFile) }
    }

    @Test fun onlyExpiredShareFilesAreClearable() {
        val expired = write("share/old.zip", 20).apply { assertTrue(setLastModified(old)) }
        val recent = write("share/current.zip", 40).apply { assertTrue(setLastModified(now)) }
        assertEquals(20L, LogsAndCachesCleaner.cachesSize(tmp.root, now))
        LogsAndCachesCleaner.clearCaches(tmp.root, now)
        assertFalse(expired.exists())
        assertTrue(recent.isFile)
        assertEquals(0L, LogsAndCachesCleaner.cachesSize(tmp.root, now))
    }

    @Test fun anOldDirectoryWithARecentlyWrittenChildSurvives() {
        val active = write("export-staging/run/file", 30).apply { assertTrue(setLastModified(now)) }
        assertTrue(active.parentFile!!.setLastModified(old))
        assertEquals(0L, LogsAndCachesCleaner.cachesSize(tmp.root, now))
        LogsAndCachesCleaner.clearCaches(tmp.root, now)
        assertTrue(active.isFile)
    }

    @Test fun expiredShareDirectoriesAreCountedAndDeletedTogether() {
        val stale = write("shared/run/file", 15).apply { assertTrue(setLastModified(old)) }
        assertTrue(stale.parentFile!!.setLastModified(old))
        assertEquals(15L, LogsAndCachesCleaner.cachesSize(tmp.root, now))
        LogsAndCachesCleaner.clearCaches(tmp.root, now)
        assertFalse(stale.parentFile!!.exists())
    }

    @Test fun clearingACacheLinkDoesNotTouchItsTarget() {
        val external = tmp.newFolder("external")
        val kept = File(external, "keep").apply { writeText("user data") }
        val cache = tmp.newFolder("cache")
        assumeTrue(runCatching { Files.createSymbolicLink(File(cache, "models-cache").toPath(), external.toPath()) }.isSuccess)
        LogsAndCachesCleaner.clearCaches(cache, now)
        assertEquals("user data", kept.readText())
        assertFalse(Files.isSymbolicLink(File(cache, "models-cache").toPath()))
    }
}
