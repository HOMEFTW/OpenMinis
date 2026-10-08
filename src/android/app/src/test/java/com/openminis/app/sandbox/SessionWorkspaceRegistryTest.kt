package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.test.runTest

class SessionWorkspaceRegistryTest {
    @Test fun `cold grandchild restore survives later registration through unloaded parent`() = runTest {
        val parents = mapOf("cold-grandchild" to "cold-child", "cold-child" to "cold-root")
        try {
            assertEquals("cold-root", SessionWorkspaceRegistry.restore("cold-grandchild") { parents[it] })
            SessionWorkspaceRegistry.register("cold-grandchild", "cold-child")
            SessionWorkspaceRegistry.register("cold-sibling", "cold-child")
            assertEquals("cold-root", SessionWorkspaceRegistry.owner("cold-grandchild"))
            assertEquals("cold-root", SessionWorkspaceRegistry.owner("cold-sibling"))
        } finally {
            (parents.keys + listOf("cold-root", "cold-sibling")).forEach(SessionWorkspaceRegistry::forget)
        }
    }

    @Test fun `cycle or failed database read leaves the previous mapping intact`() = runTest {
        try {
            SessionWorkspaceRegistry.register("restore-child", "restore-root")
            val cycle = runCatching {
                SessionWorkspaceRegistry.restore("restore-child") {
                    if (it == "restore-child") "restore-parent" else "restore-child"
                }
            }
            assertEquals(true, cycle.exceptionOrNull() is IllegalArgumentException)
            assertEquals("restore-root", SessionWorkspaceRegistry.owner("restore-child"))
            val failedRead = runCatching { SessionWorkspaceRegistry.restore("restore-child") { error("database unavailable") } }
            assertEquals(true, failedRead.isFailure)
            assertEquals("restore-root", SessionWorkspaceRegistry.owner("restore-child"))
        } finally {
            listOf("restore-child", "restore-parent", "restore-root").forEach(SessionWorkspaceRegistry::forget)
        }
    }
    @Test fun `nested children resolve the root even when parent is registered later`() {
        try {
            SessionWorkspaceRegistry.register("registry-grandchild", "registry-child")
            SessionWorkspaceRegistry.register("registry-child", "registry-root")
            assertEquals("registry-root", SessionWorkspaceRegistry.owner("registry-grandchild"))
            assertThrows(IllegalArgumentException::class.java) {
                SessionWorkspaceRegistry.register("registry-root", "registry-grandchild")
            }
            assertEquals("registry-root", SessionWorkspaceRegistry.owner("registry-grandchild"))
        } finally {
            listOf("registry-grandchild", "registry-child", "registry-root").forEach(SessionWorkspaceRegistry::forget)
        }
    }
}
