package com.openminis.app.backup

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupHistoryLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val records = ListSerializer(BackupHistory.Record.serializer())
    private val direct = Executor { it.run() }
    private fun record(id: String = "run", log: List<BackupHistory.LogEntry> = emptyList()) = BackupHistory.Record(
        id = id, backupId = "package", startedAt = System.currentTimeMillis(), status = BackupHistory.Status.SUCCEEDED,
        destinations = listOf(BackupHistory.DestinationOutcome("NAS", true, path = "/backups")), log = log,
    )

    @Test fun `legacy history is bounded once while its original file and all non log fields survive`() {
        val dir = temporary.newFolder()
        val original = record(log = (0 until 17_000).map { BackupHistory.LogEntry(it.toLong(), "Durable outcome $it") })
        val raw = json.encodeToString(records, listOf(original))
        File(dir, "records.json").writeText(raw)
        val history = BackupHistory(dir, direct)
        val loaded = history.records().single()
        history.flushWrites()
        assertEquals(original.copy(log = emptyList()), loaded.copy(log = emptyList()))
        assertEquals(201, loaded.log.size)
        assertEquals(16_800, BackupLogRules.omittedCount(loaded.log.first()))
        assertEquals(raw, File(dir, "records.json.bak").readText())
        assertEquals(listOf(loaded), BackupHistory(dir, direct).records())
        assertEquals(raw, File(dir, "records.json.bak").readText())
    }

    @Test fun `many cache updates coalesce into one queued write of the latest state`() {
        val dir = temporary.newFolder()
        val pending = mutableListOf<Runnable>()
        val history = BackupHistory(dir, Executor { pending += it })
        repeat(100) { history.upsert(record().copy(totalBytes = it.toLong())) }
        assertEquals(1, pending.size)
        assertFalse(File(dir, "records.json").exists())
        pending.removeAt(0).run()
        assertEquals(99L, json.decodeFromString(records, File(dir, "records.json").readText()).single().totalBytes)
        assertEquals(99L, history.records().single().totalBytes)
    }

    @Test fun `slow disk writes do not hold the cache lock or block a newer upsert`() {
        val dir = temporary.newFolder()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val history = BackupHistory(dir, executor)
        history.writeHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            history.upsert(record("first"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val updater = Executors.newSingleThreadExecutor()
            try { updater.submit { history.upsert(record("latest")); history.records() }.get(2, TimeUnit.SECONDS) }
            finally { updater.shutdownNow() }
            assertEquals(setOf("first", "latest"), history.records().map { it.id }.toSet())
            release.countDown()
            history.flushWrites()
            assertEquals(2, json.decodeFromString(records, File(dir, "records.json").readText()).size)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `corrupt history is preserved and a failing sanitizer never erases a record`() {
        val corruptDir = temporary.newFolder()
        File(corruptDir, "records.json").writeText("[{broken")
        assertTrue(BackupHistory(corruptDir, direct).records().isEmpty())
        assertEquals("[{broken", corruptDir.listFiles()!!.single().readText())
        val dir = temporary.newFolder()
        val original = record(log = listOf(BackupHistory.LogEntry(1, "Evidence")))
        File(dir, "records.json").writeText(json.encodeToString(records, listOf(original)))
        assertEquals(listOf(original), BackupHistory(dir, direct) { error("cleaner failed") }.records())
    }

    @Test fun `destination rename preserves delivery details and old records lack transient safely`() {
        val dir = temporary.newFolder()
        val history = BackupHistory(dir, direct)
        val original = record()
        history.upsert(original)
        history.renameDestination("NAS", "New NAS")
        assertEquals(original.destinations.single().copy(name = "New NAS"), history.records().single().destinations.single())
        val old = json.decodeFromString(BackupHistory.LogEntry.serializer(), """{"at":1,"message":"older"}""")
        assertFalse(old.isTransient)
    }

    @Test fun `progress and duplicate callbacks stay bounded without losing problems`() {
        val log = mutableListOf<BackupHistory.LogEntry>()
        repeat(10_000) { BackupLogRules.append(log, BackupHistory.LogEntry(it.toLong(), "Saving to NAS… ${it / 100}%", isTransient = true)) }
        assertEquals(1, log.size)
        val failure = BackupHistory.LogEntry(10_001, log.last().message, isProblem = true)
        assertTrue(BackupLogRules.append(log, failure))
        BackupLogRules.append(log, BackupHistory.LogEntry(10_002, "Delivered"))
        assertTrue(failure in log)
        assertTrue(failure in BackupLogRules.sanitize(log))
        var now = 0L
        val gate = PercentGate(clock = { now })
        assertTrue(gate.admit(1)); assertFalse(gate.admit(1)); assertFalse(gate.admit(2))
        now = 1_000L
        assertTrue(gate.admit(2))
    }
}
