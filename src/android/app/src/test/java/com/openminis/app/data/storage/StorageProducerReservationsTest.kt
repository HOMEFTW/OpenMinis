package com.openminis.app.data.storage

import com.openminis.app.data.session.SessionResourceCopier
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.ComposerDraft
import com.openminis.app.ui.chat.ComposerDraftStore
import com.openminis.app.ui.chat.DraftAttachment
import com.openminis.app.ui.chat.DraftResourceMigration
import com.openminis.app.util.MemorySharedPreferences
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageProducerReservationsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun repositoryPreservesTruncatedToolInputWhileItsWriteProtectsAllStorageRoots() = runBlocking {
        val parts = """[{"type":"toolUse","value":{"toolUseId":"tool","name":"shell","input":"{\"command\":"}}]"""
        var saved: MessageEntity? = null
        var updated: String? = null
        val gate = SessionStorageMaintenanceGate.shared
        fun assertReserved() = runBlocking {
            gate.withOrphanMaintenance {
                assertNull(it.claimIfUnreserved("unrelated-workspace"))
                assertNull(it.claimIfUnreserved("unrelated-media", media = true))
            }
        }
        val defaults = Class.forName("${ChatDao::class.java.name}\$DefaultImpls")
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { proxy, method, args ->
            val arguments = args ?: emptyArray()
            val implementation = defaults.methods.firstOrNull {
                it.name == method.name && it.parameterCount == arguments.size + 1
            }
            if (implementation != null) {
                try { implementation.invoke(null, proxy, *arguments) }
                catch (e: InvocationTargetException) { throw e.targetException }
            } else when (method.name) {
                "nextSortOrder" -> 0
                "insertMessageRaw" -> { assertReserved(); saved = arguments[0] as MessageEntity; Unit }
                "updateMessagePartsRaw" -> { assertReserved(); updated = arguments[1] as String; Unit }
                "updateLastMessage", "touchSession" -> Unit
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ChatDao
        val repo = ChatRepository(dao)
        val message = repo.appendMessage("session", "assistant", parts)
        assertEquals(parts, saved?.partsJson)
        assertEquals(parts, message.partsJson)
        repo.updateMessageParts(message.id, parts)
        assertEquals(parts, updated)
        assertTrue(gate.reservedSessionIds().isEmpty())
        assertTrue(runCatching { SessionStorageReferences.idsForParts(parts) }.isFailure)
    }

    @Test fun historyCopierPreservesDamagedToolInputWithoutInterpretingIt() {
        val parts = """[{"type":"toolUse","value":{"input":"{\"command\":"}}]"""
        val copied = SessionResourceCopier(temp.newFolder("files"), "copy").rewrite(parts)
        assertEquals("{\"command\":", org.json.JSONArray(copied).getJSONObject(0).getJSONObject("value").getString("input"))
        assertTrue(SessionStorageMaintenanceGate.shared.reservedSessionIds().isEmpty())
    }

    @Test fun mediaWriteIsReservedUntilTheActualStreamCopyCompletesAndFailureReleasesIt() = runBlocking {
        val root = temp.newFolder("files")
        val store = MediaStore(root)
        val gate = SessionStorageMaintenanceGate.shared
        var checked = false
        val failing = object : InputStream() {
            override fun read(): Int {
                runBlocking { gate.withOrphanMaintenance { assertNull(it.claimIfUnreserved("stream-owner")) } }
                checked = true
                throw IOException("Source failed")
            }
        }
        assertNull(store.saveMediaStreamed(failing, "text/plain", "stream-owner"))
        assertTrue(checked)
        assertFalse(gate.reservedSessionIds().contains("stream-owner"))
        assertTrue(store.mediaBaseDir.walkTopDown().none { it.isFile })
        gate.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("stream-owner")!!.use {
                assertThrows(StorageMaintenanceBusyException::class.java) {
                    store.saveMedia(byteArrayOf(1), "text/plain", "stream-owner")
                }
                assertNull(store.saveMediaStreamed(byteArrayOf(1).inputStream(), "text/plain", "stream-owner"))
            }
        }
    }

    @Test fun savedDraftReferencesProtectSourcesAndCannotBeIntroducedDuringTheirDeletion() = runBlocking {
        val store = ComposerDraftStore(MemorySharedPreferences())
        val draft = ComposerDraft("/private/minis-sessions/text-source/a",
            listOf(DraftAttachment("a", "file", "/private/minis-sessions/source/a", "text/plain", "FILE")))
        SessionStorageMaintenanceGate.shared.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("source")!!.use {
                assertThrows(StorageMaintenanceBusyException::class.java) { store.save("__new__draft", draft) }
                assertNull(store.load("__new__draft"))
            }
        }
        SessionStorageMaintenanceGate.shared.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("text-source")!!.use {
                assertThrows(StorageMaintenanceBusyException::class.java) { store.save("__new__draft", draft) }
            }
        }
        store.save("__new__draft", draft)
        assertEquals(setOf("__new__draft", "source", "text-source"), store.protectedStorageIds())
        val broken = ComposerDraftStore(MemorySharedPreferences(mapOf("broken" to """{"text":"","attachments":{}}""")))
        assertTrue(runCatching { broken.protectedStorageIds() }.isFailure)
    }

    @Test fun migrationAndWorkspaceCopiesReserveBothSourceAndDestinationBeforeFileAccess() = runBlocking {
        val root = temp.newFolder("files")
        val source = File(root, "minis-sessions/source/workspace/a").apply { parentFile!!.mkdirs(); writeText("original") }
        val destination = File(root, "minis-sessions/destination")
        val copier = SessionResourceCopier(root, "destination")
        SessionStorageMaintenanceGate.shared.withOrphanMaintenance { maintenance ->
            maintenance.claimIfUnreserved("source")!!.use {
                assertThrows(StorageMaintenanceBusyException::class.java) { copier.copyWorkspace("source") }
                assertThrows(StorageMaintenanceBusyException::class.java) {
                    DraftResourceMigration.migrate(File(root, "minis-sessions/source"), destination)
                }
                assertEquals("original", source.readText())
                assertFalse(destination.exists())
            }
        }
        copier.copyWorkspace("source")
        assertEquals("original", File(destination, "workspace/a").readText())
        assertTrue(SessionStorageMaintenanceGate.shared.reservedSessionIds().isEmpty())
    }
}
