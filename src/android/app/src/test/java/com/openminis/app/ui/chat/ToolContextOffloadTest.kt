package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolContextOffloadTest {
    @Test fun editSavesBothArgumentsAndPreservesEmptyReplacement() {
        val part = AgentContentPart.ToolUse("edit", "file_edit", JSONObject()
            .put("path", "/tmp/a").put("old_string", "a".repeat(900)).put("new_string", "b".repeat(800)))
        val saved = mutableMapOf<String, String>()
        val result = offloadToolArguments(part, 500, 600) { field, content ->
            saved[field] = content
            "/var/minis/offloads/$field.txt"
        }!!
        assertEquals(setOf("old_string", "new_string"), saved.keys)
        assertEquals("/tmp/a", result.input.getString("path"))
        saved.keys.forEach { assertTrue(result.input.getString(it).contains("/$it.txt")) }
        assertEquals(900, part.input.getString("old_string").length)
        assertTrue(result.isOffloadedArgument)
        val deletion = part.copy(input = JSONObject(part.input.toString()).put("new_string", ""))
        val deleted = offloadToolArguments(deletion, 0, 400) { field, _ -> "/$field" }!!
        assertEquals("", deleted.input.getString("new_string"))
    }

    @Test fun imageOffloadKeepsShortResultAndBothLongResultPaths() {
        val original = AgentContentPart.ToolResult("image", "browser_use", "screenshot caption", false,
            imageData = ByteArray(2048), imageMimeType = "image/png")
        val result = offloadToolResult(original, 500, 2100,
            writeText = { fail("Short text must remain inline"); "" }, writeImage = { "/images/one.png" })!!
        assertTrue(result.content.contains("screenshot caption"))
        assertTrue(result.content.contains("/images/one.png"))
        assertEquals("/images/one.png", result.imageLinuxPath)
        assertNull(result.imageData)
        val long = offloadToolResult(original.copy(content = "x".repeat(1000)), 800, 3100,
            writeText = { "/texts/one.txt" }, writeImage = { "/images/one.png" })!!
        assertTrue(long.content.contains("/texts/one.txt"))
        assertTrue(long.content.contains("/images/one.png"))
    }

    @Test fun textOnlyOffloadDoesNotDropSmallImage() {
        val pixels = ByteArray(100)
        val part = AgentContentPart.ToolResult("a", "read_image", "x".repeat(600), false,
            imageData = pixels, imageMimeType = "image/png", imageLinuxPath = "/original.png")
        val result = offloadToolResult(part, 100, 700,
            writeText = { "/saved.txt" }, writeImage = { fail("Small image must stay"); "" })!!
        assertSame(pixels, result.imageData)
        assertEquals("image/png", result.imageMimeType)
        assertEquals("/original.png", result.imageLinuxPath)
    }

    @Test fun partialSaveFailureDoesNotReplaceOriginalParts() {
        val args = AgentContentPart.ToolUse("edit", "file_edit", JSONObject()
            .put("old_string", "a".repeat(600)).put("new_string", "b".repeat(600)))
        assertNull(offloadToolArguments(args, 500, 100) { field, _ -> if (field == "old_string") "/old" else "" })
        assertEquals("b".repeat(600), args.input.getString("new_string"))
        val part = AgentContentPart.ToolResult("a", "read_image", "x".repeat(600), false, imageData = ByteArray(2000))
        assertNull(offloadToolResult(part, 100, 2600, writeText = { "/text" }, writeImage = { "" }))
        assertEquals(600, part.content.length)
        assertEquals(2000, part.imageData!!.size)
    }

    @Test fun signedToolArgumentsStayExactInsteadOfBreakingReplay() {
        val part = AgentContentPart.ToolUse("signed", "file_write", JSONObject().put("content", "x".repeat(800)),
            thoughtSignature = "opaque-signature")
        assertTrue(offloadableToolArguments(part, 0).isEmpty())
        assertNull(offloadToolArguments(part, 500, 100) { _, _ -> fail("Signed input must stay exact"); "" })
    }
}
