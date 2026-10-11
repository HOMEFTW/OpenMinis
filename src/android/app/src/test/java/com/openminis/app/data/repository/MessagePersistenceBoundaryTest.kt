package com.openminis.app.data.repository

import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupRecordMapper
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.ui.chat.EditBranchMedia
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files

class MessagePersistenceBoundaryTest {
    private fun brokenParts() = """[{"type":"toolUse","value":{"toolUseId":"call_old","name":"shell_execute","input":"{\"command\":\"echo hello\"}","description":"read","pageURL":"","imageFilePath":"","thoughtSignature":null,"isOffloadedArgument":false}]"""
    private fun row(parts: String = brokenParts(), id: String = "row_0", order: Int = 0) =
        MessageEntity(id, "session", "assistant", parts, order.toLong(), sortOrder = order,
            reasoningContent = "reason", modelId = "test-model", errorInfo = "error")

    /** Execute production DAO default methods, with only SQLite query methods substituted. */
    private fun dao(query: (String, Array<out Any?>) -> Any?): ChatDao {
        val defaults = Class.forName("${ChatDao::class.java.name}\$DefaultImpls")
        return Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { proxy, method, args ->
            val arguments = args ?: emptyArray()
            val implementation = defaults.methods.firstOrNull {
                it.name == method.name && it.parameterCount == arguments.size + 1
            }
            if (implementation == null) query(method.name, arguments) else try {
                implementation.invoke(null, proxy, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ChatDao
    }

    private fun daoForRows(rows: List<MessageEntity>, oversized: Set<String> = emptySet()): ChatDao = dao { name, args ->
        fun page(): List<MessageEntity> {
            val ranged = if (name == "loadMessagesPageInRangeRaw" || name == "loadMessageMetadataPage") {
                rows.filter { (args[3] == null || it.createdAt >= args[3] as Long) &&
                    (args[4] == null || it.createdAt <= args[4] as Long) }
            } else rows
            return ranged.drop(args[1] as Int).take(args[2] as Int)
        }
        when (name) {
            "messageCountForSession" -> rows.size
            "loadMessagesPageRaw", "loadMessagesPageInRangeRaw" -> page().also {
                if (it.any { row -> row.id in oversized }) throw IllegalStateException("CursorWindow overflow")
            }
            "loadMessageMetadataPage" -> page().map { it.copy(partsJson = "", reasoningContent = null) }
            "loadMessageColumnChunk" -> {
                val message = rows.single { it.id == args[0] }
                val text = if (args[1] == true) message.reasoningContent else message.partsJson
                text?.let {
                    val points = it.codePoints().toArray()
                    val start = (args[2] as Int - 1).coerceAtMost(points.size)
                    String(points, start, (args[3] as Int).coerceAtMost(points.size - start))
                }
            }
            "observeMessageIds" -> flowOf(rows.map { it.id })
            "branchMessageRaw" -> rows.firstOrNull { it.id == args[0] }?.also {
                if (it.id in oversized) throw IllegalStateException("CursorWindow overflow")
            }
            "lastMessagePartsRaw" -> rows.lastOrNull()?.let {
                if (it.id in oversized) throw IllegalStateException("CursorWindow overflow")
                it.partsJson
            }
            "messageHeadNewestFirstRaw" -> rows.reversed().getOrNull(args[1] as Int)?.let {
                if (it.id in oversized) throw IllegalStateException("CursorWindow overflow")
                com.openminis.app.data.db.MessageHeadRow(it.id, it.role, it.partsJson)
            }
            "lastMessageTailPerSessionRaw" -> rows.lastOrNull()?.let {
                if (it.id in oversized) throw IllegalStateException("CursorWindow overflow")
                listOf(com.openminis.app.data.db.SessionTailRow(it.sessionId, it.role, it.partsJson))
            }.orEmpty()
            "messageMetadataById" -> rows.firstOrNull { it.id == args[0] }?.copy(partsJson = "", reasoningContent = null)
            "messageMetadataNewestFirst" -> rows.reversed().getOrNull(args[1] as Int)?.copy(partsJson = "", reasoningContent = null)
            "messageSessionIds" -> rows.map { it.sessionId }.distinct()
            else -> error("Unexpected query $name")
        }
    }

    @Test fun allPublicDaoMessageReadsAndRepositoryExportsRecoverLegacyParts() = runBlocking {
        val original = row()
        val dao = daoForRows(listOf(original))
        val repository = ChatRepository(dao)
        val readable = requireNotNull(repairLegacyAssistantPartsJson(original.partsJson))
        assertEquals(readable, dao.loadMessages("session").single().partsJson)
        assertEquals(readable, dao.loadMessagesPage("session", 0, 1).single().partsJson)
        assertEquals(readable, dao.loadMessagesPageInRange("session", 0, 1, 0L, 0L).single().partsJson)
        assertEquals(readable, dao.observeMessages("session").first().single().partsJson)
        assertEquals(readable, dao.branchMessage(original.id, "session")!!.partsJson)
        assertEquals(readable, dao.lastMessageParts("session"))
        assertEquals(readable, dao.messageHeadNewestFirst("session", 0)!!.partsJson)
        assertEquals(readable, dao.lastMessageTailPerSession().single().partsJson)
        assertEquals(readable, repository.loadMessagePageRaw("session", 0, 1).single().partsJson)
        assertEquals("call_old", repository.loadMessagePage("session", 0, 1, includeTools = true).single().toolUses.single().toolUseId)
        assertTrue(SessionSearch.searchSnippet(original.partsJson, "hello")!!.contains("echo hello"))
        assertEquals(original, dao.loadMessages("session").single().copy(partsJson = original.partsJson))
    }

    @Test fun backupsRecoverLegacyPartsAndNeverSilentlyEmptyUnrecoverableMessages() {
        val original = row()
        val restored = requireNotNull(BackupRecordMapper.message(BackupExporter.messageRecord(original).jsonObject))
        assertEquals("call_old", JSONArray(restored.partsJson).getJSONObject(0).getJSONObject("value").getString("toolUseId"))
        assertTrue(runCatching { BackupExporter.messageRecord(original.copy(partsJson = "[{broken")) }.isFailure)
        assertTrue(runCatching { BackupExporter.messageRecord(original.copy(partsJson = "null")) }.isFailure)
    }

    @Test fun editBranchAcceptsLegacyPartsWithoutChangingArguments() {
        val root = Files.createTempDirectory("minis-branch-persistence").toFile()
        try {
            val rewritten = EditBranchMedia(root, "branch").rewrite(brokenParts())
            val input = JSONArray(rewritten).getJSONObject(0).getJSONObject("value").getString("input")
            assertEquals("echo hello", JSONObject(input).getString("command"))
        } finally { root.deleteRecursively() }
    }

    @Test fun oversizedMiddleRowIsReconstructedWithoutLosingItsIdentityOrUnicode() = runBlocking {
        val content = "🐲".repeat(70_000)
        val parts = JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", "call_big").put("name", "shell").put("success", true).put("output", content))).toString()
        val middle = row(parts, "row_1", 1)
        val rows = listOf(row("[]"), middle, row("[]", "row_2", 2))
        val repository = ChatRepository(daoForRows(rows, setOf(middle.id)))
        val loaded = repository.loadMessages("session")
        assertEquals(rows, loaded)
        assertEquals(content, JSONArray(loaded[1].partsJson).getJSONObject(0).getJSONObject("value").getString("output"))
        assertEquals(listOf(middle), repository.loadMessagePageRaw("session", 1, 1))
    }

    @Test fun oversizedFilteredRowUsesTheSameDateRangeDuringFallback() = runBlocking {
        val rows = listOf(row("[]"), row(id = "row_1", order = 1), row("[]", "row_2", 2))
        val dao = daoForRows(rows, setOf("row_1"))
        val loaded = dao.loadMessagesPageInRange("session", 0, 1, 1L, 1L).single()
        assertEquals("row_1", loaded.id)
        assertEquals("call_old", JSONArray(loaded.partsJson).getJSONObject(0).getJSONObject("value").getString("toolUseId"))
    }

    @Test fun oversizedTailAndBranchTargetAreReconstructedInsteadOfDropped() = runBlocking {
        val original = row()
        val dao = daoForRows(listOf(original), setOf(original.id))
        val readable = requireNotNull(repairLegacyAssistantPartsJson(original.partsJson))
        assertEquals(original.copy(partsJson = readable), dao.branchMessage(original.id, "session"))
        assertEquals(readable, dao.lastMessageParts("session"))
        assertEquals(readable, dao.messageHeadNewestFirst("session", 0)!!.partsJson)
        assertEquals(readable, dao.lastMessageTailPerSession().single().partsJson)
    }

    @Test fun restoreInsertAndUpdateShareTheBoundedToolStructurePolicy() = runBlocking {
        val parts = JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", "call_big").put("name", "shell").put("success", true)
            .put("output", "x".repeat(800_000)))).toString()
        var inserted: MessageEntity? = null
        var updated: String? = null
        val dao = dao { name, args ->
            when (name) {
                "insertMessageRaw" -> { inserted = args[0] as MessageEntity; Unit }
                "updateMessagePartsRaw" -> { updated = args[1] as String; Unit }
                else -> error("Unexpected query $name")
            }
        }
        val original = row(parts)
        dao.insertMessage(original)
        dao.updateMessageParts(original.id, parts)
        val restored = BackupRecordMapper.message(BackupExporter.messageRecord(original).jsonObject)!!
        assertEquals(inserted!!.partsJson, updated)
        assertEquals(inserted!!.partsJson, restored.partsJson)
        assertTrue(restored.partsJson.length <= MESSAGE_PARTS_CHAR_BUDGET)
        assertEquals("call_big", JSONArray(restored.partsJson).getJSONObject(0).getJSONObject("value").getString("toolUseId"))
    }

    @Test fun signedReasoningOver64kSurvivesStorageBackupAndChunkedReads() = runBlocking {
        val reasoning = "signed thinking 🐲\n".repeat(6_000)
        assertTrue(reasoning.length > 64_000)
        val signature = "original-signed-block"
        for (replayType in listOf("anthropicReplay", "deepSeekReplay")) {
            val replay = JSONArray().put(JSONObject().put("type", "thinking")
                .put("thinking", reasoning).put("signature", signature)).toString()
            val parts = JSONArray().put(JSONObject().put("type", replayType).put("value", replay)).toString()
            val original = row(parts).copy(reasoningContent = reasoning)
            var stored: MessageEntity? = null
            val writeDao = dao { name, args ->
                if (name != "insertMessageRaw") error("Unexpected query $name")
                stored = args[0] as MessageEntity
                Unit
            }
            writeDao.insertMessage(original)
            assertEquals(original, stored)
            val restored = BackupRecordMapper.message(BackupExporter.messageRecord(stored!!).jsonObject)!!
            assertEquals(reasoning, restored.reasoningContent)
            assertEquals(replay, JSONArray(restored.partsJson).getJSONObject(0).getString("value"))
            val readDao = daoForRows(listOf(restored), setOf(restored.id))
            val loaded = readDao.loadMessages("session").single()
            assertEquals(reasoning, loaded.reasoningContent)
            val reloadedReplay = JSONArray(JSONArray(loaded.partsJson).getJSONObject(0).getString("value"))
            assertEquals(reasoning, reloadedReplay.getJSONObject(0).getString("thinking"))
            assertEquals(signature, reloadedReplay.getJSONObject(0).getString("signature"))
        }
    }
}
