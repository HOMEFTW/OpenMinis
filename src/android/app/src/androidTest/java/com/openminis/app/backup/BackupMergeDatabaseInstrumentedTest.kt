package com.openminis.app.backup

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.CompactMarkerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupMergeDatabaseInstrumentedTest {
    @Test fun cancelledMergeRemovesItsNewRowsAndPreservesAnOlderEmptySession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val existing = ChatSessionEntity("existing", title = "user draft", modelId = "model", createdAt = 1, updatedAt = 1)
            dao.insertSession(existing)
            try {
                db.withTransaction {
                    dao.insertSession(existing.copy(id = "incoming", title = "incoming"))
                    dao.insertMessage(MessageEntity("incoming-message", "incoming", "user", "[]", 2, sortOrder = 0))
                    throw CancellationException("stopped during message merge")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertNull(dao.getSession("incoming"))
            assertEquals(existing, dao.getSession("existing"))
            assertEquals(0, dao.messageCountForSession("existing"))
            assertEquals(listOf("existing"), dao.listSessionIds())
        } finally { db.close() }
    }

    @Test fun failedMergeKeepsTheLocalSessionAndItsMessage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val local = ChatSessionEntity("s", title = "local", modelId = "model", createdAt = 1, updatedAt = 1)
            val message = MessageEntity("m", "s", "user", "[]", 1, sortOrder = 0, updatedAt = 1)
            dao.insertSession(local); dao.insertMessage(message)
            try {
                db.withTransaction {
                    dao.insertSession(local.copy(title = "incoming"))
                    dao.insertMessage(message.copy(partsJson = "[{\"type\":\"text\",\"text\":\"incoming\"}]"))
                    throw java.io.IOException("unreadable backup record")
                }
                fail("Expected failure")
            } catch (_: java.io.IOException) { }
            assertEquals(local, dao.getSession("s"))
            assertEquals(listOf(message), dao.loadMessages("s"))
        } finally { db.close() }
    }

    @Test fun updatingSessionPreservesLocalChildrenAndCancelledMergeRollsBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val local = ChatSessionEntity("s", title = "local", modelId = "model", createdAt = 1, updatedAt = 1)
            val message = MessageEntity("m", "s", "user", "[]", 1, sortOrder = 0, updatedAt = 1)
            val marker = CompactMarkerEntity("c", "s", "summary", 0, 1, 1)
            dao.insertSession(local); dao.insertMessage(message); dao.insertCompactMarker(marker)
            dao.insertSession(local.copy(title = "new metadata", updatedAt = 2))
            assertEquals(1, dao.messageCountForSession("s"))
            assertEquals(listOf(marker), dao.listCompactMarkers("s"))
            try {
                db.withTransaction {
                    dao.insertSession(local.copy(title = "cancelled", updatedAt = 3))
                    dao.insertMessage(message.copy(partsJson = "[{\"type\":\"text\",\"text\":\"incoming\"}]", updatedAt = 3))
                    throw CancellationException("restore stopped")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals("new metadata", dao.getSession("s")!!.title)
            assertEquals(message, dao.loadMessages("s").single())
            assertEquals(listOf(marker), dao.listCompactMarkers("s"))
        } finally { db.close() }
    }
}
