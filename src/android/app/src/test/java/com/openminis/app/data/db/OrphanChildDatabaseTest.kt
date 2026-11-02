package com.openminis.app.data.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class OrphanChildDatabaseTest {
    private val expected = listOf("child", "root")
    private fun row(id: String, parent: String? = null, updated: Long = 1) =
        ChatSessionEntity(id = id, modelId = "model", createdAt = 1, updatedAt = updated, parentSessionId = parent)

    private fun withTree(block: suspend (AppDatabase, ChatDao) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            dao.insertSession(row("root", "missing-parent"))
            dao.insertSession(row("child", "root"))
            dao.insertSession(row("unrelated"))
            dao.insertMessage(MessageEntity("message", "child", "user", "[]", 1, sortOrder = 0))
            block(db, dao)
        } finally { db.close() }
    }

    @Test fun unchangedOrphanTreeDeletesOnlyItsRowsAndMessages() = withTree { _, dao ->
        assertTrue(dao.deleteOrphanTreeIfUnchanged("root", expected, 100))
        assertNull(dao.getSession("root"))
        assertNull(dao.getSession("child"))
        assertTrue(dao.loadMessages("child").isEmpty())
        assertNotNull(dao.getSession("unrelated"))
    }

    @Test fun changesAfterSelectionAbortTheWholeDelete() {
        for (change in listOf("child-added", "parent-restored", "session-active", "new-message")) {
            withTree { _, dao ->
                when (change) {
                    "child-added" -> dao.insertSession(row("late-child", "root"))
                    "parent-restored" -> dao.insertSession(row("missing-parent"))
                    "session-active" -> dao.touchSession("child", 100)
                    "new-message" -> dao.insertMessage(MessageEntity("recent", "child", "user", "[]", 100, sortOrder = 1))
                }
                assertFalse(change, dao.deleteOrphanTreeIfUnchanged("root", expected, 100))
                assertNotNull(change, dao.getSession("root"))
                assertNotNull(change, dao.getSession("child"))
                assertTrue(change, dao.loadMessages("child").any { it.id == "message" })
            }
        }
    }

    @Test fun failureDuringDeletionRollsBackPreviouslyDeletedChildrenAndMessages() = withTree { db, dao ->
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_root_delete BEFORE DELETE ON sessions
            WHEN OLD.id = 'root' BEGIN SELECT RAISE(ABORT, 'test deletion failure'); END
        """.trimIndent())
        val failure = runCatching { dao.deleteOrphanTreeIfUnchanged("root", expected, 100) }.exceptionOrNull()
        assertNotNull(failure)
        assertNotNull(dao.getSession("root"))
        assertNotNull(dao.getSession("child"))
        assertEquals("message", dao.loadMessages("child").single().id)
    }

    @Test fun childCreationCannotRecreateADeletedParentTree() = withTree { _, dao ->
        dao.deleteSession("root")
        assertTrue(runCatching { dao.insertChildSession(row("new-child", "root")) }.exceptionOrNull() is IllegalStateException)
        assertNull(dao.getSession("new-child"))
    }
}
