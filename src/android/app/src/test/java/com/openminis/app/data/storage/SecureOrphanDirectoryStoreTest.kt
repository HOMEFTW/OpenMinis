package com.openminis.app.data.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureOrphanDirectoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(root: File, path: String, value: String = "abc"): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(value)
    }
    private fun supported(root: File) = Files.newDirectoryStream(root.toPath()).use { it is SecureDirectoryStream<*> }

    @Test fun providerWithoutSecureStreamsRefusesCleanupAndPreservesRealFiles() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(!supported(root))
        val original = file(root, "minis-sessions/orphan/workspace/file.txt")
        val store = SecureOrphanDirectoryStore(root)
        assertTrue(runCatching { store.scan(StorageProtection(emptySet())) }.exceptionOrNull() is IOException)
        assertEquals("abc", original.readText())
    }

    @Test fun scansOnlyActualWorkspaceDateAndBranchLayoutsAndKeepsDraftsAndUnknownLayouts() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(supported(root))
        file(root, "minis-sessions/orphan/workspace/a")
        file(root, "minis-sessions/owned/workspace/a")
        file(root, "minis-sessions/__new__lost/workspace/a")
        file(root, "media/2026/10/14/orphan/a")
        file(root, "media/branches/branch/a")
        file(root, "media/2026/02/30/invalid/a")
        file(root, "media/unknown/nested/a")
        file(root, "browser_tabs/orphan.json")
        val plan = SecureOrphanDirectoryStore(root).scan(StorageProtection(setOf("owned")))
        assertEquals(listOf("branch", "orphan", "orphan"), plan.targets.map { it.sessionId }.sorted())
        assertEquals(9L, plan.bytes)
    }

    @Test fun unchangedConfirmedTreeIsDeletedAndOnlyItsFileBytesAreCounted() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(supported(root))
        file(root, "minis-sessions/orphan/workspace/a")
        file(root, "minis-sessions/orphan/attachments/b", "12345")
        val outsidePlan = file(root, "minis-sessions/__new__keep/workspace/a")
        val store = SecureOrphanDirectoryStore(root)
        val plan = store.scan(StorageProtection(emptySet()))
        var bytes = 0L
        assertTrue(store.delete(plan.targets.single()) { bytes += it })
        assertEquals(8L, bytes)
        assertFalse(File(root, "minis-sessions/orphan").exists())
        assertTrue(outsidePlan.exists())
    }

    @Test fun newlyAddedFilesAfterConfirmationKeepTheWholeTarget() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(supported(root))
        val original = file(root, "minis-sessions/orphan/workspace/a")
        val store = SecureOrphanDirectoryStore(root)
        val plan = store.scan(StorageProtection(emptySet()))
        file(root, "minis-sessions/orphan/workspace/new")
        assertFalse(store.delete(plan.targets.single()) { fail("No unlink before full snapshot recheck") })
        assertTrue(original.exists())
    }

    @Test fun replacementIdentityAtTheSameNameIsKept() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(supported(root))
        file(root, "minis-sessions/orphan/workspace/a")
        val store = SecureOrphanDirectoryStore(root)
        val plan = store.scan(StorageProtection(emptySet()))
        val target = File(root, "minis-sessions/orphan")
        assertTrue(target.renameTo(File(root, "minis-sessions/moved")))
        val replacement = file(root, "minis-sessions/orphan/workspace/a", "new")
        assertFalse(store.delete(plan.targets.single()) { fail("Replacement cannot be deleted") })
        assertEquals("new", replacement.readText())
    }

    @Test fun symlinkInTreeAndReplacedParentCannotReachOutsidePackage() = runBlocking {
        val root = temp.newFolder("files")
        assumeTrue(supported(root))
        val outside = temp.newFolder("outside")
        val external = file(outside, "orphan/workspace/a", "external")
        val original = file(root, "minis-sessions/orphan/workspace/a")
        val store = SecureOrphanDirectoryStore(root)
        val plan = store.scan(StorageProtection(emptySet()))
        val sessions = File(root, "minis-sessions")
        assertTrue(sessions.renameTo(File(root, "old-sessions")))
        Files.createSymbolicLink(sessions.toPath(), outside.toPath())
        assertFalse(store.delete(plan.targets.single()) { fail("External files cannot be unlinked") })
        assertEquals("external", external.readText())
        assertTrue(File(root, "old-sessions/orphan/workspace/a").exists())
        Files.delete(sessions.toPath())
        assertTrue(File(root, "old-sessions").renameTo(sessions))
        Files.createSymbolicLink(File(original.parentFile, "link").toPath(), external.toPath())
        val linked = store.scan(StorageProtection(emptySet()))
        assertTrue(linked.targets.isEmpty())
        assertEquals(1, linked.kept)
        assertEquals("external", external.readText())
    }
}
