package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryImageLoaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun onlyRecentBudgetedImagesAreReadAndHistoryKeepsNoPixels() {
        val files = (1..3).map { folder.newFile().apply { writeBytes(ByteArray(4)) } }
        val history = files.map { file -> LLMMessage(LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ImageData(byteArrayOf(), "image/webp",
                localPath = file.path, linuxPath = "/var/minis/${file.name}"))) }
        val read = mutableListOf<String>()
        var elisions = 0
        val outgoing = loadHistoryImages(history, 8, onBudgetElision = { elisions += it.droppedCount }) { file, _ ->
            read += file.path
            LLMMessage.ImagePart(file.readBytes(), "image/jpeg")
        }
        assertEquals(listOf(files[2].path, files[1].path), read)
        assertEquals(1, elisions)
        assertTrue(outgoing.first().contentParts.single() is AgentContentPart.Text)
        assertEquals("image/jpeg", (outgoing.last().contentParts.single() as AgentContentPart.ImageData).mimeType)
        assertTrue(history.all { (it.contentParts.single() as AgentContentPart.ImageData).data.isEmpty() })
    }

    @Test fun missingOrUnreadableImagesGetExplicitPlaceholders() {
        val file = folder.newFile()
        val message = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ImageData(byteArrayOf(), "image/png", localPath = file.path),
            AgentContentPart.ImageData(byteArrayOf(), "image/png", localPath = file.path + ".missing"),
        ))
        val result = loadHistoryImages(listOf(message)) { _, _ -> null }
        assertTrue(result.single().contentParts.all {
            it is AgentContentPart.Text && it.text.contains("unavailable")
        })
    }
}
