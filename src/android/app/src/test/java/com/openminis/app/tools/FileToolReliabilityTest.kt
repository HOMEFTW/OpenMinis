package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FileToolReliabilityTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val mount = "/file-tool-regression"

    @Before fun setUp() {
        directory = Files.createTempDirectory("file-tools-").toFile()
        context = object : ContextWrapper(null) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = directory
            override fun getApplicationContext(): Context = this
        }
        PRootKernel.bindMounts[mount] = directory.absolutePath
    }

    @After fun tearDown() {
        PRootKernel.bindMounts.remove(mount)
        directory.deleteRecursively()
    }

    private fun edit(marker: String) = FileEditTool.execute(JSONObject()
        .put("path", "$mount/parallel.txt").put("old_string", "old$marker")
        .put("new_string", "new$marker").toString(), "fixture", context)

    private fun write(path: String, content: String, append: Boolean = true) =
        FileWriteTool.execute(JSONObject().put("path", path).put("content", content)
            .put("append", append).toString(), "fixture", context)

    @Test fun `parallel edits of different markers retain both successful changes`() {
        val file = File(directory, "parallel.txt")
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(4) {
                file.writeText("oldA" + "x".repeat(2 * 1024 * 1024) + "oldB")
                val go = CountDownLatch(1)
                val futures = listOf("A", "B").map { marker -> pool.submit<ToolExecutionResult> {
                    go.await(); edit(marker)
                } }
                go.countDown()
                assertTrue(futures.all { it.get(5, TimeUnit.SECONDS).success })
                val text = file.readText()
                assertTrue(text.startsWith("newA"))
                assertTrue(text.endsWith("newB"))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `append uses the same lock across canonical aliases and other files stay usable`() {
        val file = File(directory, "parallel.txt").apply { writeText("oldA") }
        val pool = Executors.newFixedThreadPool(2)
        try {
            lateinit var writer: java.util.concurrent.Future<ToolExecutionResult>
            FileMutationCoordinator.withFile(file) {
                val ready = CountDownLatch(1)
                writer = pool.submit<ToolExecutionResult> {
                    ready.countDown(); write("$mount/./parallel.txt", "-append")
                }
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                // An independent file can finish while the first file is locked.
                assertTrue(pool.submit<ToolExecutionResult> { write("$mount/other.txt", "other") }
                    .get(2, TimeUnit.SECONDS).success)
                assertFalse(writer.isDone)
                assertEquals("oldA", file.readText())
                assertTrue(edit("A").success)
            }
            assertTrue(writer.get(2, TimeUnit.SECONDS).success)
            assertEquals("newA-append", file.readText())
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed edit releases the lock without changing the file`() {
        val file = File(directory, "parallel.txt").apply { writeText("no match") }
        assertFalse(edit("A").success)
        assertEquals("no match", file.readText())
        assertTrue(write("$mount/parallel.txt", "-ok").success)
        assertEquals("no match-ok", file.readText())
    }

    @Test fun `interrupted lock waiter performs no mutation and later writes succeed`() {
        val file = File(directory, "parallel.txt").apply { writeText("original") }
        var result: ToolExecutionResult? = null
        lateinit var worker: Thread
        FileMutationCoordinator.withFile(file) {
            worker = Thread { result = write("$mount/parallel.txt", "-cancelled") }.apply { start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.state != Thread.State.WAITING && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, worker.state)
            worker.interrupt()
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
        assertEquals(false, result?.success)
        assertEquals("original", file.readText())
        assertTrue(write("$mount/parallel.txt", "-ok").success)
        assertEquals("original-ok", file.readText())
    }
}
