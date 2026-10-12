package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftResourceMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedDestinationPreservesSourceAndCanBeRetried() {
        val source = temp.newFolder("draft")
        val payload = File(source, "workspace/fixture.txt").apply {
            requireNotNull(parentFile).mkdirs(); writeText("original")
        }
        val destination = temp.newFolder("real")
        val blocker = File(destination, "workspace").apply { writeText("blocker") }
        assertFalse(DraftResourceMigration.migrate(source, destination))
        assertEquals("original", payload.readText())
        assertEquals("blocker", blocker.readText())
        assertTrue(blocker.delete())
        assertTrue(DraftResourceMigration.migrate(source, destination))
        assertEquals("original", File(destination, "workspace/fixture.txt").readText())
        assertFalse(source.exists())
    }

    @Test fun conflictingFileDoesNotDestroyEitherVersion() {
        val source = temp.newFolder("conflict-draft")
        val destination = temp.newFolder("conflict-real")
        val old = File(source, "attachments/same.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("draft") }
        val current = File(destination, "attachments/same.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("real") }
        assertFalse(DraftResourceMigration.migrate(source, destination))
        assertEquals("draft", old.readText())
        assertEquals("real", current.readText())
    }

    @Test fun identicalCompletedCopyIsAcknowledgedWithoutOverwriting() {
        val source = temp.newFile("source.txt").apply { writeText("same bytes") }
        val destination = temp.newFile("destination.txt").apply { writeText("same bytes") }
        assertTrue(DraftResourceMigration.move(source, destination))
        assertFalse(source.exists())
        assertEquals("same bytes", destination.readText())
    }
}
