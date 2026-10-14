package com.openminis.app.sandbox

import android.content.ContextWrapper
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FreshProcessShellTest {
    private class BlockingStream : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int { closed.await(); return -1 }
        override fun close() { closed.countDown() }
    }
    private class FakeProcess(val source: InputStream, private val code: Int = 0) : Process() {
        @Volatile var destroyed = false
        override fun getInputStream() = source
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = code
        override fun destroy() { destroyed = true; source.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun <T> inDirectory(block: (File) -> T): T {
        val dir = Files.createTempDirectory("fresh-shell").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun splitUtf8AndShortFinalLineReachResultAndCallback() = inDirectory { dir -> runBlocking {
        val text = "中文🙂测试\n尾行"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val split = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        val lines = mutableListOf<String>()
        val shell = FreshProcessShell(ContextWrapper(null), "test", emptyMap(), statusDirectory = dir,
            processFactory = { _, _, _ -> FakeProcess(split, 7) })
        val result = shell.execute("ignored", 5000, lineCallback = lines::add)
        assertEquals(text, result.first)
        assertEquals(7, result.second)
        assertEquals(listOf("中文🙂测试", "尾行"), lines)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    } }

    @Test fun timeoutKillsBlockedReaderAndReturns124() = inDirectory { dir -> runBlocking {
        val process = FakeProcess(BlockingStream())
        val shell = FreshProcessShell(ContextWrapper(null), "test", emptyMap(), statusDirectory = dir,
            processFactory = { _, _, _ -> process })
        val result = withTimeout(5000) { shell.execute("ignored", 100) }
        assertEquals(124, result.second)
        assertTrue(result.first.contains("timed out"))
        assertTrue(process.destroyed)
    } }

    @Test fun coroutineCancellationStopsTheProcess() = inDirectory { dir -> runBlocking {
        val process = FakeProcess(BlockingStream())
        val started = CompletableDeferred<Unit>()
        val shell = FreshProcessShell(ContextWrapper(null), "test", emptyMap(), statusDirectory = dir,
            processFactory = { _, _, _ -> started.complete(Unit); process })
        val job = launch { shell.execute("ignored", 60000) }
        withTimeout(5000) { started.await() }
        job.cancelAndJoin()
        withTimeout(5000) { while (!process.destroyed || dir.listFiles().orEmpty().isNotEmpty()) delay(10) }
        assertTrue(job.isCancelled)
    } }

    @Test fun stopBeforeStartingNeverSpawns() = inDirectory { dir -> runBlocking {
        var spawned = false
        val shell = FreshProcessShell(ContextWrapper(null), "test", emptyMap(), statusDirectory = dir,
            processFactory = { _, _, _ -> spawned = true; FakeProcess(ByteArrayInputStream(byteArrayOf())) })
        shell.stop()
        assertEquals(130, shell.execute("ignored", 1000).second)
        assertFalse(spawned)
    } }

    @Test fun detachedProcessKeepsSessionAndMountedOwnerReservedUntilActualExit() = inDirectory { dir -> runBlocking {
        val exited = CountDownLatch(1)
        val process = object : Process() {
            val source = BlockingStream()
            override fun getInputStream(): InputStream = source
            override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun waitFor(): Int { exited.await(); return 0 }
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
            override fun isAlive(): Boolean = exited.count > 0
            override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { exited.countDown(); source.close() }
        }
        val sid = "detached-${java.util.UUID.randomUUID()}"
        val owner = "mounted-${java.util.UUID.randomUUID()}"
        val gate = SessionStorageMaintenanceGate.shared
        val shell = FreshProcessShell(ContextWrapper(null), sid,
            mapOf("/var/minis/workspace" to File(dir, "minis-sessions/$owner/workspace").absolutePath),
            statusDirectory = dir, processFactory = { _, _, status ->
                File(status).writeText("0")
                process
            })
        try {
            val result = withTimeout(5000) { shell.execute("detached", 60000) }
            assertEquals(0, result.second)
            assertTrue("Detached process must keep running", process.isAlive)
            gate.withOrphanMaintenance { maintenance ->
                assertNull("Command return must not release its session files", maintenance.claimIfUnreserved(sid))
                assertNull("The process also owns its mounted workspace", maintenance.claimIfUnreserved(owner))
            }
        } finally {
            process.destroy()
            withTimeout(5000) {
                while (sid in gate.reservedSessionIds() || owner in gate.reservedSessionIds()) delay(10)
            }
        }
    } }

    @Test fun stdoutAndStderrKeepBoundedHeadAndTail() {
        val output = BoundedShellOutput(10).append("head").append("x".repeat(100_000)).append("tail")
        assertTrue(output.toString().startsWith("head"))
        assertTrue(output.toString().endsWith("tail"))
        assertTrue(output.toString().length < 100)
        assertTrue(output.toString().contains("99988 characters omitted"))
        val input = "[native_offload] diagnostic\n真实错误\n" + "x".repeat(300_000) + "\nend\n"
        val logged = mutableListOf<String>()
        val kept = StderrDrain(input.byteInputStream(), logged::add).start().finish(2000)
        assertEquals(listOf("[native_offload] diagnostic"), logged)
        assertTrue(kept.startsWith("真实错误"))
        assertTrue(kept.endsWith("end\n"))
        assertTrue(kept.length < 41_000)
    }
}
