package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantPartsPersistenceTest {
    private fun decode(parts: List<AgentContentPart>, meta: Map<String, AssistantBlock> = emptyMap(), replay: String? = null): JSONArray {
        val encoded = serializeAssistantParts(parts, meta, replay, anthropicReplay = null)
        // org.json accepts some malformed arrays; also require strict JSON.
        assertTrue(Json.parseToJsonElement(encoded) is JsonArray)
        return JSONArray(encoded)
    }

    @Test fun nestedShellArgumentsRoundTripWithQuotesSlashesAndUnicode() {
        val command = "echo \"=== memory ===\"; for f in 2026-10-09 2026-10-10; do cat /var/minis/memory/\$f.md 2>/dev/null; done; grep -n \"10-11\\|测试\" /tmp/notes.md\n"
        val input = JSONObject().put("command", command).put("tool_title", "读取近日记忆")
        val value = decode(listOf(AgentContentPart.ToolUse("call_1", "shell_execute", input)))
            .getJSONObject(0).getJSONObject("value")
        assertEquals(command, JSONObject(value.getString("input")).getString("command"))
        assertEquals("读取近日记忆", JSONObject(value.getString("input")).getString("tool_title"))
        assertTrue(value.has("thoughtSignature"))
        assertTrue(value.isNull("thoughtSignature"))
        assertFalse(value.getBoolean("isOffloadedArgument"))
    }

    @Test fun skippedPartsNeverProduceEmptyArraySlotsAndOrderIsPreserved() {
        val blank = AgentContentPart.ToolUse("blank", "  ", JSONObject())
        val result = AgentContentPart.ToolResult("call_1", "shell_execute", "done")
        val image = AgentContentPart.ImageData(byteArrayOf(1), "image/png")
        val array = decode(listOf(blank, AgentContentPart.Text("before"), result,
            AgentContentPart.ToolUse("call_1", "shell_execute", JSONObject()), blank,
            AgentContentPart.Text("after"), image))
        assertEquals(3, array.length())
        assertEquals("before", array.getJSONObject(0).getString("value"))
        assertEquals("toolUse", array.getJSONObject(1).getString("type"))
        assertEquals("after", array.getJSONObject(2).getString("value"))
        assertEquals(0, decode(listOf(blank, result, image)).length())
        assertEquals(0, decode(emptyList()).length())
    }

    @Test fun metadataAndOpaqueThoughtSignatureArePreserved() {
        val meta = AssistantBlock("call_1", "tool_use", toolTitle = "查看\"页面\"",
            browserURL = "https://example.com/?q=a&b=c", imageFilePath = "screenshots/页面.png")
        val tool = AgentContentPart.ToolUse("call_1", "browser", JSONObject(),
            thoughtSignature = "sig/+=\"\\\n", isOffloadedArgument = true)
        val value = decode(listOf(tool), mapOf(tool.id to meta)).getJSONObject(0).getJSONObject("value")
        assertEquals(meta.toolTitle, value.getString("description"))
        assertEquals(meta.browserURL, value.getString("pageURL"))
        assertEquals(meta.imageFilePath, value.getString("imageFilePath"))
        assertEquals(tool.thoughtSignature, value.getString("thoughtSignature"))
        assertTrue(value.getBoolean("isOffloadedArgument"))
    }

    @Test fun deepSeekReplayCanBeAppendedAfterMultipleToolCalls() {
        val tools = (1..2).map { AgentContentPart.ToolUse("call_$it", "shell_execute", JSONObject().put("command", "echo $it")) }
        val replay = "{\"blocks\":[{\"thinking\":\"保留\\n原文\"}]}"
        val array = decode(tools, replay = replay)
        assertEquals(3, array.length())
        assertEquals("call_2", array.getJSONObject(1).getJSONObject("value").getString("toolUseId"))
        assertEquals("deepSeekReplay", array.getJSONObject(2).getString("type"))
        assertEquals(replay, array.getJSONObject(2).getString("value"))
    }

    @Test fun anthropicSignedReplaySurvivesSerializationWithoutEscapingChanges() {
        val replay = """{"version":1,"blocks":[{"type":"thinking","signature":"opaque/+=\\n"},{"type":"redacted_thinking","data":"encoded/=="}]}"""
        val encoded = serializeAssistantParts(listOf(AgentContentPart.Text("answer")), anthropicReplay = replay)
        val value = JSONArray(encoded).getJSONObject(1)
        assertEquals("anthropicReplay", value.getString("type"))
        assertEquals(replay, value.getString("value"))
        assertTrue(Json.parseToJsonElement(encoded) is JsonArray)
    }
}
