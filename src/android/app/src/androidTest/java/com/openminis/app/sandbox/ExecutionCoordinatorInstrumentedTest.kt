package com.openminis.app.sandbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for ExecutionCoordinator.
 * Tests per-command mounts, concurrent isolation, and cancellation.
 *
 * Run on real device: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExecutionCoordinatorInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetKernel()
        ExecutionCoordinator.init(context)

        if (canBoot()) {
            PRootKernel.boot(context)
        }
    }

    @After
    fun tearDown() {
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
        cleanupSessionDirs()
    }

    // ==================== Session mounting ====================

    @Test
    fun executeRecordsItsWorkspaceOwner() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-mount-test"
        ExecutionCoordinator.execute(sessionId, "echo hello")

        assertEquals(sessionId, ExecutionCoordinator.mountedSessionIdFor(sessionId))
    }

    @Test
    fun executePreservesIndependentWorkspaceOwners() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-A", "echo a")
        assertEquals("session-A", ExecutionCoordinator.mountedSessionIdFor("session-A"))

        ExecutionCoordinator.execute("session-B", "echo b")
        assertEquals("session-B", ExecutionCoordinator.mountedSessionIdFor("session-B"))
        assertEquals("session-A", ExecutionCoordinator.mountedSessionIdFor("session-A"))
    }

    @Test
    fun executeUsesAFreshProcessWithTheSameWorkspace() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-X", "export MINIS_TEST_LOCAL=first; echo first > /var/minis/workspace/process-test.txt")
        val second = ExecutionCoordinator.execute("session-X", "echo env=\$MINIS_TEST_LOCAL; cat /var/minis/workspace/process-test.txt")
        assertTrue(second.output.contains("first"))
        assertFalse(second.output.contains("env=first"))
    }

    @Test
    fun executeKeepsSessionMountsOutOfTheGlobalMap() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-mounts", "echo test")

        val built = SessionMounts.forContext(context, "session-mounts")
        assertTrue(built.skipped.isEmpty())
        for (subdir in SessionMounts.SESSION_SUBDIRS) {
            assertTrue(built.mounts.containsKey("/var/minis/$subdir"))
            assertFalse(PRootKernel.bindMounts.containsKey("/var/minis/$subdir"))
        }
        for (subdir in SessionMounts.GLOBAL_SUBDIRS) {
            assertTrue(built.mounts.containsKey("/var/minis/$subdir"))
        }
    }

    @Test
    fun executeCreatesHostDirectories() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-dirs-test"
        ExecutionCoordinator.execute(sessionId, "echo test")

        // Verify session host directories exist
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        for (subdir in listOf("attachments", "offloads", "workspace", "browser")) {
            assertTrue("$subdir should exist", File(sessionBase, subdir).isDirectory)
        }

        // Verify global host directories exist
        val globalBase = File(context.filesDir, "minis-global")
        for (subdir in SessionMounts.GLOBAL_SUBDIRS) {
            assertTrue("$subdir should exist", File(globalBase, subdir).isDirectory)
        }
    }

    @Test
    fun sessionBindMountsPointToCorrectHostDirs() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-verify-paths"
        ExecutionCoordinator.execute(sessionId, "echo test")

        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        val mounts = SessionMounts.forContext(context, sessionId).mounts
        assertEquals(
            File(sessionBase, "workspace").absolutePath,
            mounts["/var/minis/workspace"]
        )

        val globalBase = File(context.filesDir, "minis-global")
        assertEquals(
            File(globalBase, "memory").absolutePath,
            mounts["/var/minis/memory"]
        )
    }

    // ==================== Command execution ====================

    @Test
    fun executeReturnsCorrectOutput() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-output", "echo hello world")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello world"))
    }

    @Test
    fun executeAppendsExitCodeOnFailure() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-fail", "exit 1")
        assertEquals(1, result.exitCode)
        assertTrue("Should append exit code", result.output.contains("(exit code: 1)"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnSuccess() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-ok", "echo ok")
        assertEquals(0, result.exitCode)
        assertFalse("Should not contain exit code marker", result.output.contains("(exit code:"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnTimeout() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-timeout", "sleep 60", timeout = 2_000)
        assertEquals(124, result.exitCode)
        assertFalse("Should not double-append exit code", result.output.contains("(exit code: 124)"))
    }

    @Test
    fun executeWithLineCallback() = runBlocking {
        skipIfNoBoot()

        val lines = mutableListOf<String>()
        ExecutionCoordinator.execute(
            "session-callback",
            "echo one; echo two",
            lineCallback = { lines.add(it) }
        )

        assertTrue(lines.size >= 2)
        assertTrue(lines.contains("one"))
        assertTrue(lines.contains("two"))
    }

    // ==================== Concurrent isolation ====================

    @Test
    fun concurrentCallsKeepTheirOwnWorkspaceMounts() = runBlocking {
        skipIfNoBoot()

        val deferred1 = async {
            ExecutionCoordinator.execute("session-concurrent-A", "echo A > /var/minis/workspace/concurrent.txt; sleep 1; cat /var/minis/workspace/concurrent.txt")
        }
        val deferred2 = async {
            delay(50)
            ExecutionCoordinator.execute("session-concurrent-B", "echo B > /var/minis/workspace/concurrent.txt; sleep 1; cat /var/minis/workspace/concurrent.txt")
        }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed
        assertEquals(0, result1.exitCode)
        assertEquals(0, result2.exitCode)

        assertEquals("A", result1.output.trim())
        assertEquals("B", result2.output.trim())
    }

    // ==================== sessionDidTerminate ====================

    @Test
    fun sessionDidTerminateStopsOnlyItsRunningCommand() = runBlocking {
        skipIfNoBoot()

        val ready = CompletableDeferred<Unit>()
        val command = async {
            ExecutionCoordinator.execute("session-terminate", "echo ready; sleep 30; echo unexpected", lineCallback = {
                if (it == "ready") ready.complete(Unit)
            })
        }
        withTimeout(10_000) { ready.await() }
        ExecutionCoordinator.sessionDidTerminate("session-terminate")
        command.join()
        assertTrue(command.isCancelled)
        assertEquals(0, ExecutionCoordinator.execute("session-keep", "echo still-running").exitCode)
    }

    @Test
    fun sessionDidTerminatePreservesWorkspaceOwnership() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-keep", "echo test")
        SessionWorkspaceRegistry.register("session-owner-child", "session-keep")
        ExecutionCoordinator.sessionDidTerminate("session-other")
        ExecutionCoordinator.sessionDidTerminate("session-owner-child")
        assertEquals("session-keep", ExecutionCoordinator.mountedSessionIdFor("session-owner-child"))
        assertEquals(0, ExecutionCoordinator.execute("session-keep", "echo kept").exitCode)
    }

    @Test
    fun sessionDidTerminateIsNoOpWhenNoSession() {
        ExecutionCoordinator.sessionDidTerminate("any-session")
        assertEquals("any-session", ExecutionCoordinator.mountedSessionIdFor("any-session"))
    }

    // ==================== stopCurrentCommand ====================

    @Test
    fun stopCurrentCommandIsNoOpWhenIdle() {
        ExecutionCoordinator.stopCurrentCommand() // Should not throw
    }

    // ==================== Cross-session file isolation ====================

    @Test
    fun differentSessionsHaveIsolatedWorkspaces() = runBlocking {
        skipIfNoBoot()

        // Write file in session A's workspace
        ExecutionCoordinator.execute(
            "session-iso-A",
            "echo 'from A' > /var/minis/workspace/test.txt"
        )

        // Check it exists in session A
        val resultA = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA.output.contains("from A"))

        // Switch to session B — workspace should be empty
        val resultB = ExecutionCoordinator.execute(
            "session-iso-B",
            "ls /var/minis/workspace/"
        )
        assertFalse("Session B should not see session A's file", resultB.output.contains("test.txt"))

        // Switch back to A — file should still be there
        val resultA2 = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA2.output.contains("from A"))
    }

    @Test
    fun globalDirsAreSharedAcrossSessions() = runBlocking {
        skipIfNoBoot()

        // Write file to global memory in session A
        ExecutionCoordinator.execute(
            "session-global-A",
            "echo 'shared' > /var/minis/memory/coordinator-test-shared.txt"
        )

        // Read from session B — should see the same file
        val result = ExecutionCoordinator.execute(
            "session-global-B",
            "cat /var/minis/memory/coordinator-test-shared.txt"
        )
        assertTrue("Global dirs should be shared", result.output.contains("shared"))
    }

    // ==================== Helpers ====================

    private fun canBoot(): Boolean {
        // AAPT may package the gzip archive as a tar; match RootfsManager's
        // supported names so an installed app does not silently skip the suite.
        val rootfsAvailable = listOf("alpine-minirootfs.tar.gz", "alpine-minirootfs.tar").any { asset ->
            runCatching { context.assets.open(asset).use { } }.isSuccess
        }
        val prootAvailable = File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile
        return rootfsAvailable && prootAvailable
    }

    private fun skipIfNoBoot() {
        assumeTrue("PRoot assets must be available", PRootKernel.isBooted)
    }

    private fun resetKernel() {
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
    }

    private fun cleanupSessionDirs() {
        listOf("session-mount-test", "session-A", "session-B", "session-X",
            "session-mounts", "session-dirs-test", "session-verify-paths",
            "session-output", "session-fail", "session-ok", "session-timeout",
            "session-callback", "session-concurrent-A", "session-concurrent-B", "session-terminate", "session-owner-child",
            "session-keep", "session-iso-A", "session-iso-B",
            "session-global-A", "session-global-B").forEach { id ->
            File(context.filesDir, "minis-sessions/$id").deleteRecursively()
            SessionWorkspaceRegistry.forget(id)
        }
        File(context.filesDir, "minis-global/memory/coordinator-test-shared.txt").delete()
    }
}
