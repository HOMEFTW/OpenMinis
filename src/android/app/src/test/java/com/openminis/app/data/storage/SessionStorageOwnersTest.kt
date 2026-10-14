package com.openminis.app.data.storage

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionStorageOwnersTest {
    @Test fun inactiveSavedChildAndMessageOwnerProtectMissingParentsAndKnownWorkspaceOwners() = runBlocking {
        val parents = mapOf("child" to "missing-parent", "message-only" to "middle", "middle" to "missing-ancestor")
        val ids = SessionStorageOwners.collect(listOf("child", "message-only"), { parents[it] },
            { if (it == "child") "known-workspace" else it })
        assertEquals(setOf("child", "missing-parent", "message-only", "middle", "missing-ancestor", "known-workspace"), ids)
        val protection = StorageProtection(ids)
        assertTrue(protection.protects("missing-parent", false))
        assertTrue(protection.protects("missing-ancestor", false))
        assertTrue(protection.protects("known-workspace", false))
    }

    @Test fun cyclesAndFailedParentQueriesInvalidateTheWholeSnapshot() = runBlocking {
        assertTrue(runCatching {
            SessionStorageOwners.collect(listOf("a"), { if (it == "a") "b" else "a" }, { it })
        }.isFailure)
        assertTrue(runCatching {
            SessionStorageOwners.collect(listOf("a"), { throw IOException("Unreadable owner") }, { it })
        }.isFailure)
        assertTrue(runCatching {
            SessionStorageOwners.collect(listOf("a"), { "../outside" }, { it })
        }.isFailure)
    }
}
