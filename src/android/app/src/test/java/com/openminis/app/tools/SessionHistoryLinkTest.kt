package com.openminis.app.tools

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SessionHistoryPage
import com.openminis.app.deeplink.SessionLink
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files

class SessionHistoryLinkTest {
    private val target = "target-session"

    private fun row(id: String, role: String, text: String, order: Int) = MessageEntity(
        id, target, role, JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString(),
        order.toLong(), sortOrder = order,
    )

    private inner class Store(val rows: List<MessageEntity>, val exists: Boolean = true) {
        val operations = mutableListOf<String>()
        val loadedIds = mutableListOf<String>()
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            operations += method.name
            when (method.name) {
                "getSession" -> if (exists && args!![0] == target) ChatSessionEntity(target, "原会话", "model", 1, 1) else null
                "messageCountForSession" -> rows.count { it.sessionId == args!![0] }
                "loadMessagesPage" -> rows.filter { it.sessionId == args!![0] }.drop(args!![1] as Int).take(args[2] as Int)
                    .also { page -> loadedIds += page.map { it.id } }
                "loadMessageMetadataPage" -> rows.filter { it.sessionId == args!![0] }.drop(args!![1] as Int).take(args[2] as Int)
                    .map { it.copy(partsJson = "") }
                else -> error("History must not write or start a session: ${method.name}")
            }
        } as ChatDao
        val repository = ChatRepository(dao)
    }

    private fun read(store: Store, args: JSONObject = JSONObject()) = runBlocking {
        if (!args.has("target_session_id")) args.put("target_session_id", SessionLink.create(target))
        SessionMessageTool.readHistory(args, store.repository)
    }

    @Test fun `a new conversation can read a linked question and answer without opting in or modifying either session`() {
        val store = Store(listOf(row("q", "user", "帮我检查构建脚本", 0), row("a", "assistant", "构建脚本在 scripts", 1)))
        val page = read(store)
        assertEquals(target, page.getString("session_id"))
        assertEquals(SessionLink.create(target), page.getString("session_link"))
        assertEquals("原会话", page.getString("title"))
        assertTrue(page.getBoolean("reference_only"))
        val messages = page.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("帮我检查构建脚本", messages.getJSONObject(0).getString("text"))
        assertEquals("构建脚本在 scripts", messages.getJSONObject(1).getString("text"))
        assertFalse(page.getBoolean("has_more"))
        assertEquals(setOf("getSession", "messageCountForSession", "loadMessagesPage"), store.operations.toSet())
    }

    @Test fun `long messages resume within the row and every character arrives exactly once`() {
        val answer = "开始" + "🙂中文🎉".repeat(8_000) + "结尾"
        val store = Store(listOf(row("q", "user", "question", 0), row("a", "assistant", answer, 1), row("next", "user", "next question", 2)))
        val received = mutableMapOf<String, StringBuilder>()
        var offset = 0
        var textOffset = 0
        var messageId: String? = null
        var done = false
        repeat(10) {
            if (done) return@repeat
            val args = JSONObject().put("offset", offset).put("text_offset", textOffset)
            messageId?.let { args.put("message_id", it) }
            val page = read(store, args)
            val messages = page.getJSONArray("messages")
            var chars = 0
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                val text = message.getString("text")
                chars += text.length
                assertTrue("Unicode chunks must contain no lone surrogates", text.codePoints().noneMatch { it in 0xD800..0xDFFF })
                received.getOrPut(message.getString("message_id")) { StringBuilder() }.append(text)
            }
            assertTrue(chars <= SessionHistoryPage.MAX_CHARS)
            val nextOffset = page.getInt("next_offset")
            val nextTextOffset = page.getInt("next_text_offset")
            done = !page.getBoolean("has_more")
            assertTrue(done || nextOffset > offset || (nextOffset == offset && nextTextOffset > textOffset))
            offset = nextOffset
            textOffset = nextTextOffset
            messageId = page.optString("next_message_id").takeIf { !page.isNull("next_message_id") }
        }
        assertTrue("Pagination must finish", done)
        assertEquals("question", received["q"].toString())
        assertEquals(answer, received["a"].toString())
        assertEquals("next question", received["next"].toString())
    }

    @Test fun `hidden rows retain database positions instead of skipping a later visible answer`() {
        val store = Store(listOf(row("hidden", "user", "<system-reminder>internal</system-reminder>", 0),
            row("q", "user", "visible question", 1), row("empty", "assistant", "", 2), row("a", "assistant", "visible answer", 3)))
        val first = read(store, JSONObject().put("limit", 2))
        assertEquals("q", first.getJSONArray("messages").getJSONObject(0).getString("message_id"))
        assertEquals(2, first.getInt("next_offset"))
        val second = read(store, JSONObject().put("offset", 2).put("limit", 2).put("message_id", first.getString("next_message_id")))
        assertEquals("a", second.getJSONArray("messages").getJSONObject(0).getString("message_id"))
        assertEquals(4, second.getInt("next_offset"))
        assertFalse(second.getBoolean("has_more"))
    }

    @Test fun `a full text budget does not load later large messages into memory`() {
        val store = Store(listOf(row("first", "assistant", "x".repeat(SessionHistoryPage.MAX_CHARS + 1), 0),
            row("later", "assistant", "later content", 1)))
        val page = read(store)
        assertTrue(page.getBoolean("has_more"))
        assertEquals(0, page.getInt("next_offset"))
        assertEquals(listOf("first"), store.loadedIds)
    }

    @Test fun `deleting the source row between pages cannot resume inside a different message`() {
        val rows = mutableListOf(row("first", "assistant", "a".repeat(40_000), 0),
            row("second", "assistant", "b".repeat(40_000), 1))
        val store = Store(rows)
        val first = read(store)
        rows.removeAt(0)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            read(store, JSONObject().put("offset", first.getInt("next_offset"))
                .put("text_offset", first.getInt("next_text_offset"))
                .put("message_id", first.getString("next_message_id")))
        }
        assertTrue(failure.message!!.contains("History changed"))
        val restarted = read(store, JSONObject().put("offset", 0).put("text_offset", 0)
            .put("message_id", first.getString("next_message_id")))
        assertEquals("second", restarted.getJSONArray("messages").getJSONObject(0).getString("message_id"))
        assertEquals(0, restarted.getJSONArray("messages").getJSONObject(0).getInt("text_offset"))
    }

    @Test fun `offloaded history has early continuation metadata and can be fully read with default file windows`() {
        val text = "汉字🙂\\\"".repeat(8_000)
        val page = read(Store(listOf(row("source", "assistant", text, 0))))
        val serialized = SessionMessageTool.formatHistory(page)
        assertEquals(page.getInt("count"), JSONObject(serialized).getJSONArray("messages").length())
        val file = Files.createTempFile("minis-history-", ".json").toFile()
        try {
            file.writeText(serialized)
            var offset = 1
            val fragments = mutableListOf<String>()
            repeat(20) {
                if (offset < 0) return@repeat
                val window = FileReadWindow.read(file, offset, null, 15_000, false)
                if (offset == 1) assertTrue(window.content.contains("\"next_message_id\""))
                fragments += window.content
                assertTrue("Offloaded lines must have a continuation", !window.truncated || window.nextOffset != null)
                offset = window.nextOffset?.toInt() ?: -1
            }
            assertEquals(-1, offset)
            val messages = JSONObject(fragments.joinToString("\n")).getJSONArray("messages")
            val recovered = (0 until messages.length()).joinToString("") { messages.getJSONObject(it).getString("text") }
            assertEquals((0 until page.getJSONArray("messages").length()).joinToString("") {
                page.getJSONArray("messages").getJSONObject(it).getString("text")
            }, recovered)
            assertTrue(recovered.codePoints().noneMatch { it in 0xD800..0xDFFF })
        } finally { file.delete() }
    }

    @Test fun `tool previews do not leave half an emoji in referenced history`() {
        val parts = JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", "call").put("success", true).put("output", "x".repeat(199) + "🙂")))
        val page = read(Store(listOf(MessageEntity("tool", target, "assistant", parts.toString(), 1, sortOrder = 0))))
        val preview = page.getJSONArray("messages").getJSONObject(0).getString("text")
        assertTrue(preview.codePoints().noneMatch { it in 0xD800..0xDFFF })
        assertEquals("[Tool result: ${"x".repeat(199)}]", preview)
    }

    @Test fun `empty sessions are distinct from missing sessions and invalid links`() {
        val empty = read(Store(emptyList()))
        assertEquals(0, empty.getInt("total"))
        assertFalse(empty.getBoolean("has_more"))
        val missing = Store(emptyList(), exists = false)
        val failure = assertThrows(IllegalArgumentException::class.java) { read(missing) }
        assertTrue(failure.message!!.contains("not found on this device"))
        assertEquals(listOf("getSession"), missing.operations)
        val wrongApp = Store(emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            read(wrongApp, JSONObject().put("target_session_id", "codex://threads/other"))
        }
        assertTrue(wrongApp.operations.isEmpty())
    }

    @Test fun `invalid cursor and excessive page sizes fail without reading transcript rows`() {
        for (args in listOf(JSONObject().put("offset", -1), JSONObject().put("text_offset", -1),
            JSONObject().put("limit", 0), JSONObject().put("limit", 51), JSONObject().put("offset", 2))) {
            val store = Store(listOf(row("q", "user", "test", 0)))
            assertThrows(IllegalArgumentException::class.java) { read(store, args) }
            assertFalse(store.operations.contains("loadMessagesPage"))
        }
    }

    @Test fun `history is explicitly available in the model tool schema without changing mailbox read`() {
        val definition = SessionMessageTool.definition()
        assertEquals(listOf("list", "send", "read", "history"), definition.parameters.getValue("action").enumValues)
        assertTrue(definition.parameters.getValue("target_session_id").description.contains("minis://session/"))
        assertTrue(definition.parameters.containsKey("text_offset"))
    }
}
