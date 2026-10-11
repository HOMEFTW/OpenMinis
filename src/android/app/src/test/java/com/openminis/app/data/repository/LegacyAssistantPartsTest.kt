package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LegacyAssistantPartsTest {
    // Same missing outer brace as the old Kotlin string interpolation. No user data.
    private fun brokenTool(id: String = "call_1", command: String = "echo \"测试\"; cat /tmp/\$f.md | tail -n 30") =
        """{"type":"toolUse","value":{"toolUseId":"$id","name":"shell_execute","input":${JSONObject.quote(JSONObject().put("command", command).toString())},"description":"读取记忆","pageURL":"","imageFilePath":"","thoughtSignature":null,"isOffloadedArgument":false}"""

    @Test fun reproducesReportedUnterminatedObjectAndRecoversArgumentsExactly() {
        val raw = "[${brokenTool()}]"
        assertTrue(runCatching { JSONArray(raw) }.isFailure)
        val repaired = requireNotNull(repairLegacyAssistantPartsJson(raw))
        Json.parseToJsonElement(repaired)
        assertEquals("[${brokenTool()}}]", repaired)
        val value = JSONArray(repaired).getJSONObject(0).getJSONObject("value")
        assertEquals("echo \"测试\"; cat /tmp/\$f.md | tail -n 30", JSONObject(value.getString("input")).getString("command"))
        assertNull(repairLegacyAssistantPartsJson(repaired))
    }

    @Test fun repairsSeveralToolsMixedWithTextWithoutChangingOrder() {
        val first = brokenTool("call_1")
        val second = brokenTool("call_2").replace(":false}", ":true}")
        val text = """{"type":"text","value":"后续文本"}"""
        val raw = "[$first,$text,$second]"
        assertEquals("[$first},$text,$second}]", repairLegacyAssistantPartsJson(raw))
    }

    @Test fun quotedJsonAndEscapedQuotesCannotTriggerARepair() {
        val command = "echo '${brokenTool()}' \\\" } ] , { \n"
        val raw = "[${brokenTool(command = command)}]"
        val repaired = requireNotNull(repairLegacyAssistantPartsJson(raw))
        assertEquals(command, JSONObject(JSONArray(repaired).getJSONObject(0).getJSONObject("value")
            .getString("input")).getString("command"))
        val text = JSONArray().put(JSONObject().put("type", "text").put("value", raw)).toString()
        assertNull(repairLegacyAssistantPartsJson(text))
    }

    @Test fun unrelatedCorruptionIsLeftUntouched() {
        assertNull(repairLegacyAssistantPartsJson("[${brokenTool().dropLast(12)}]"))
        assertNull(repairLegacyAssistantPartsJson("[${brokenTool()}] trailing junk"))
        assertNull(repairLegacyAssistantPartsJson("[${brokenTool()}, {\"type\":"))
        assertNull(repairLegacyAssistantPartsJson("[${brokenTool().replace("toolUse\"", "unrelated\"")}]"))
        assertNull(repairLegacyAssistantPartsJson("[]"))
    }

    @Test fun repositoryReadsRecoverLegacyRowsWithoutWritingOrChangingMetadata() = runBlocking {
        val row = MessageEntity("row_1", "session_1", "assistant", "[${brokenTool()}]", 123L,
            tokenUsage = "{\"inputTokens\":42}", sortOrder = 7, reasoningContent = "thought",
            modelId = "test-model", errorInfo = "old error")
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, _ ->
            when (method.name) {
                "messageCountForSession" -> 1
                "loadMessagesPage" -> listOf(row)
                "observeMessages" -> flowOf(listOf(row))
                else -> error("Unexpected database operation: ${method.name}")
            }
        } as ChatDao
        val repository = ChatRepository(dao)
        val loaded = repository.loadMessages(row.sessionId).single()
        assertNotEquals(row.partsJson, loaded.partsJson)
        assertEquals(row, loaded.copy(partsJson = row.partsJson))
        assertEquals(loaded, repository.observeMessages(row.sessionId).first().single())
        val userRow = row.copy(role = "user")
        assertSame(userRow, userRow.withReadableAssistantParts())
    }
}
