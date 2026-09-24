package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolResultPersistenceTest {
    @Test fun toolPixelsAreStoredAsDiscoverableMediaReferences() {
        val bytes = byteArrayOf(1, 2, 3)
        val results = listOf(
            AgentContentPart.ToolResult("screenshot", "browser", "captured", imageData = bytes, imageMimeType = "image/png"),
            AgentContentPart.ToolResult("shell", "shell", "failed", isError = true),
        )
        var saved = 0
        val restored = JSONArray(serializeToolResults(results) { result ->
            saved++
            assertSame(bytes, result.imageData)
            JSONObject().put("type", "mediaRef").put("value", JSONObject()
                .put("relativePath", "session/image.png").put("mimeType", result.imageMimeType))
        })
        assertEquals(1, saved)
        assertEquals(3, restored.length())
        assertEquals("captured", restored.getJSONObject(0).getJSONObject("value").getString("output"))
        assertEquals("mediaRef", restored.getJSONObject(1).getString("type"))
        assertEquals("session/image.png", restored.getJSONObject(1).getJSONObject("value").getString("relativePath"))
        assertEquals("screenshot", restored.getJSONObject(1).getJSONObject("value").getString("toolUseId"))
        assertFalse(restored.getJSONObject(2).getJSONObject("value").getBoolean("success"))
    }
}
