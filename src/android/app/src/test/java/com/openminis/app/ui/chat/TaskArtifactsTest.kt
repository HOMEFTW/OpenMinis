package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskArtifactsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun put(path: String, modified: Long): File = File(folder.root, path).apply {
        parentFile.mkdirs(); writeText("test"); setLastModified(modified)
    }
    @Test fun ownFilesSortedAndOtherSessionsExcluded() {
        put("minis-sessions/A/attachments/old.png", 10000)
        put("minis-sessions/A/workspace/new.md", 20000)
        put("minis-sessions/B/workspace/private.md", 30000)
        put("minis-sessions/A/workspace/.env", 40000)
        put("minis-sessions/A/workspace/node_modules/pkg/a.js", 40000)
        val result = TaskArtifacts.list(folder.root, "A")
        assertEquals(listOf("new.md", "old.png"), result.files.map { it.file.name })
        assertFalse(result.limited)
    }
    @Test fun scanIsBounded() {
        repeat(501) { put("minis-sessions/A/workspace/$it.txt", it.toLong()) }
        val result = TaskArtifacts.list(folder.root, "A")
        assertEquals(500, result.files.size)
        assertTrue(result.limited)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotScanAnotherSessionViaTraversal() {
        TaskArtifacts.list(folder.root, "../B")
    }
}
