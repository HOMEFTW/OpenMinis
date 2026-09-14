package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditBranchTest {
    @Test fun branchCopiesOnlyPrefixWithoutChangingSourceOrDuplicatingUsage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            dao.insertSession(ChatSessionEntity("source", "Original", "model", 1, 1))
            val originals = (0..3).map { i -> MessageEntity("m$i", "source", if (i % 2 == 0) "user" else "assistant",
                "[]", i.toLong(), tokenUsage = "{}", sortOrder = i) }
            originals.forEach { dao.insertMessage(it) }
            dao.createEditBranch("source", "m2", "branch", "Branch")
            assertEquals(originals, dao.loadMessages("source"))
            val copied = dao.loadMessages("branch")
            assertEquals(listOf(0, 1), copied.map { it.sortOrder })
            assertTrue(copied.all { it.sessionId == "branch" && it.tokenUsage == null && it.id !in originals.map { m -> m.id } })
            try { dao.createEditBranch("source", "missing", "invalid", "Invalid"); fail("Expected missing-message failure") }
            catch (_: IllegalArgumentException) { }
            assertNull(dao.getSession("invalid"))
        } finally { db.close() }
    }
}
