package com.openminis.app.sandbox.offload

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.NativeOffloadRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SessionsCliLinkTest {
    @Test fun `messages accepts a copied conversation link without silently reading an empty wrong id`() {
        val id = "existing-session"
        val readIds = mutableListOf<String>()
        val row = MessageEntity("message-1", id, "user", """[{"type":"text","value":"原会话的问题"}]""", 1L, sortOrder = 0)
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> if (args!![0] == id) ChatSessionEntity(id, "原会话", "model", 1, 1) else null
                "loadMessagesPage" -> {
                    val target = args!![0] as String
                    readIds += target
                    if (target == id) listOf(row) else emptyList()
                }
                "messageCountForSession", "messageCountForSessionInRange" -> if (args!![0] == id) 1 else 0
                else -> error("Unexpected database operation: ${method.name}")
            }
        } as ChatDao
        val result = SessionsOffloadHandler(ChatRepository(dao)).handle(
            NativeOffloadRequest(1, listOf("minis-sessions-cli", "messages", "--id", "minis://session/$id", "--compact"), emptyMap(), "/"),
        )
        assertEquals(0, result.exitCode)
        val data = JSONObject(result.output).getJSONObject("data")
        assertEquals(id, data.getString("session_id"))
        assertEquals("原会话的问题", data.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(listOf(id), readIds)
    }

    @Test fun `deleted linked conversations report not found instead of successful empty history`() {
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, _ ->
            if (method.name == "getSession") null else error("Missing sessions must not query messages")
        } as ChatDao
        val result = SessionsOffloadHandler(ChatRepository(dao)).handle(
            NativeOffloadRequest(1, listOf("minis-sessions-cli", "messages", "--id", "minis://session/deleted", "--compact"), emptyMap(), "/"),
        )
        assertEquals(1, result.exitCode)
        val envelope = JSONObject(result.output)
        assertFalse(envelope.getBoolean("ok"))
        assertEquals("NOT_FOUND", envelope.getJSONObject("data").getString("error"))
    }
}
