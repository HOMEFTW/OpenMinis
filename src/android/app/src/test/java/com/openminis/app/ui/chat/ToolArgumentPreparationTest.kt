package com.openminis.app.ui.chat

import com.openminis.app.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentPreparationTest {
    private val shell = AgentToolDefinition("shell_execute", "Shell", mapOf(
        "command" to AgentToolParam("string", "command")), listOf("command"))

    @Test fun incompleteShellIsRejectedWithoutInventingACommand() {
        val args = JSONObject()
        val result = prepareToolArguments("shell_execute", args, """{"command":"printf hello > /tmp/draft""", listOf(shell))
        assertNotNull(result.error)
        assertEquals(0, args.length())
    }

    @Test fun invalidNonObjectAndTrailingDataNeverReachExecution() {
        for (tail in listOf("[]", "null", "{\"command\":\"ls\"}junk", "{\"command\":bad}")) {
            assertNotNull(tail, prepareToolArguments("shell_execute", JSONObject(), tail, listOf(shell)).error)
        }
    }

    @Test fun normalizedArgumentsAreExactlyWhatPersistenceSees() {
        val args = JSONObject().put("comand", "echo ok")
        val result = prepareToolArguments("shell_execute", args, null, listOf(shell))
        assertNull(result.error)
        assertEquals("echo ok", JSONObject(result.json).getString("command"))
        assertFalse(JSONObject(result.json).has("comand"))
        val parts = listOf(AgentContentPart.ToolUse("call", "shell_execute", JSONObject(result.json)))
        val saved = JSONArray(serializeAssistantParts(parts, anthropicReplay = null)).getJSONObject(0).getJSONObject("value").getString("input")
        assertEquals(args.toString(), saved)
    }

    @Test fun completeEmptyObjectAndNoDeltaCallsRemainValid() {
        assertNull(prepareToolArguments("no_args", JSONObject(), "{}", emptyList()).error)
        assertNull(prepareToolArguments("shell_execute", JSONObject().put("command", "ls"), null, listOf(shell)).error)
    }

    @Test fun truncatedHistoryCannotBeExecutedAsAnOriginalCall() {
        val args = JSONObject().put("command", "echo partial").put("_minisHistoryTruncated", true)
        assertNotNull(prepareToolArguments("shell_execute", args, null, listOf(shell)).error)
    }
}
