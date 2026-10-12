package com.openminis.app.backup

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupChatMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ref(path: String) = JSONObject().put("type", "mediaRef")
        .put("value", JSONObject().put("relativePath", path))

    @Test fun `image pasted text and nested branch media survive a clean restore`() {
        val files = tmp.newFolder("source")
        val media = File(files, "media")
        val paths = listOf("2026/10/12/s/image.png", "2026/10/12/s/paste.txt", "branches/b/tool.png")
        paths.forEachIndexed { i, path -> File(media, path).apply { parentFile!!.mkdirs(); writeText("payload-$i") } }
        val parts = JSONArray().put(ref(paths[0])).put(ref(paths[1]))
            .put(JSONObject().put("nested", JSONArray().put(ref(paths[2])))).toString()
        val pkg = tmp.newFolder("package")
        val index = File(pkg, "files.index.jsonl")
        BackupFileIndexWriter(index).use { writer ->
            val trees = BackupFileTreeExporter(BackupBlobStore(pkg, null), writer)
            val seen = mutableSetOf<String>()
            assertEquals(3, BackupChatMedia.export(parts, media, trees, seen, "s").filesIncluded)
            assertEquals(0, BackupChatMedia.export(parts, media, trees, seen, "s").filesIncluded)
        }
        val entries = index.readLines().map { BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), it) }
        val restored = tmp.newFolder("restored")
        assertEquals(3, BackupChatMedia.restore(pkg, entries, restored).written)
        paths.forEachIndexed { i, path -> assertEquals("payload-$i", File(restored, "media/$path").readText()) }
        // Old packages without the additional media prefix are still accepted.
        assertEquals(0, BackupChatMedia.restore(pkg, emptyList(), restored).written)
        File(restored, "media/${paths[0]}").writeText("local immutable copy")
        BackupChatMedia.restore(pkg, entries, restored)
        assertEquals("local immutable copy", File(restored, "media/${paths[0]}").readText())
    }
}
