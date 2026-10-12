package com.openminis.app.data.session

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionResourceCopierTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun write(root: File, path: String, content: String) = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(content)
    }
    private fun parts(path: String) = JSONArray().put(JSONObject().put("type", "mediaRef").put("value",
        JSONObject().put("relativePath", path).put("linuxPath", "/var/minis/attachments/uploads/a.pdf"))).toString()

    @Test fun `copy owns history media and Linux attachments after the source is deleted`() {
        val files = tmp.newFolder()
        val path = "2026/10/12/source/a.txt"
        write(files, "media/$path", "full pasted text")
        write(files, "minis-sessions/source/attachments/uploads/a.pdf", "pdf bytes")
        write(files, "minis-sessions/source/offloads/result.txt", "tool result")
        val copier = SessionResourceCopier(files, "copy")
        copier.copyWorkspace("source")
        val rewritten = JSONArray(copier.rewrite(parts(path))).getJSONObject(0).getJSONObject("value")
        SessionStorage(files).deleteFiles("source")
        assertEquals("full pasted text", File(files, "media/${rewritten.getString("relativePath")}").readText())
        assertEquals("pdf bytes", File(files, "minis-sessions/copy/attachments/uploads/a.pdf").readText())
        assertEquals("tool result", File(files, "minis-sessions/copy/offloads/result.txt").readText())
        assertEquals("/var/minis/attachments/uploads/a.pdf", rewritten.getString("linuxPath"))
    }

    @Test fun `failed or cancelled copy keeps source and cleanup removes only target resources`() {
        val files = tmp.newFolder()
        val source = write(files, "minis-sessions/source/workspace/a.txt", "original")
        val copier = SessionResourceCopier(files, "copy")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            copier.copyWorkspace("source") { throw java.util.concurrent.CancellationException() }
        }
        copier.cleanup()
        assertEquals("original", source.readText())
        assertFalse(File(files, "minis-sessions/copy").exists())
    }

    @Test fun `media traversal never copies outside storage`() {
        val files = tmp.newFolder()
        val external = write(files, "private.txt", "private")
        assertThrows(IllegalArgumentException::class.java) {
            SessionResourceCopier(files, "copy").rewrite(parts("../private.txt"))
        }
        assertEquals("private", external.readText())
    }

    @Test fun `an internal workspace link follows the independent copy after source deletion`() {
        val files = tmp.newFolder()
        val source = write(files, "minis-sessions/source/workspace/file.txt", "linked content")
        val link = File(source.parentFile, "link.txt")
        org.junit.Assume.assumeTrue(runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), source.toPath())
        }.isSuccess)
        SessionResourceCopier(files, "copy").copyWorkspace("source")
        SessionStorage(files).deleteFiles("source")
        assertEquals("linked content", File(files, "minis-sessions/copy/workspace/link.txt").readText())
    }

    @Test fun `workspace copy keeps external links opaque instead of copying their bytes`() {
        val files = tmp.newFolder()
        val external = write(files, "private.txt", "private")
        val workspace = File(files, "minis-sessions/source/workspace").apply { mkdirs() }
        org.junit.Assume.assumeTrue(runCatching {
            java.nio.file.Files.createSymbolicLink(File(workspace, "link").toPath(), external.toPath())
        }.isSuccess)
        val copier = SessionResourceCopier(files, "copy")
        copier.copyWorkspace("source")
        assertTrue(java.nio.file.Files.isSymbolicLink(File(files, "minis-sessions/copy/workspace/link").toPath()))
        copier.cleanup()
        assertEquals("private", external.readText())
    }
}
