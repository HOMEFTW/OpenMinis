package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DraftSessionPersistenceTest {
    private class SessionStore {
        val rows = linkedMapOf<String, ChatSessionEntity>()
        var writes = 0
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            when (method.name) {
                "insertSession" -> {
                    val row = args!![0] as ChatSessionEntity
                    rows[row.id] = row
                    writes++
                    Unit
                }
                "updateThinkingOverride" -> {
                    val id = args!![0] as String
                    rows[id]?.let { rows[id] = it.copy(thinkingOverride = args[1] as String?) }
                    writes++
                    Unit
                }
                "updateMemoryEnabled" -> {
                    val id = args!![0] as String
                    rows[id]?.let { rows[id] = it.copy(memoryEnabled = args[1] as Int) }
                    writes++
                    Unit
                }
                "getSession" -> rows[args!![0]]
                "observeSessions" -> flowOf(rows.values.toList())
                else -> error("Unexpected database operation: ${method.name}")
            }
        } as ChatDao
    }

    @Test fun `applying group defaults or toggling settings cannot persist a draft`() = runBlocking {
        val store = SessionStore()
        val repository = ChatRepository(store.dao)
        repository.updateSessionThinkingOverride("", "HIGH")
        repository.updateSessionThinkingOverride("", "OFF")
        repository.updateSessionMemoryEnabled("", false)
        // Simulate dropping the screen and rebuilding its repository without an exit callback.
        assertTrue(ChatRepository(store.dao).observeSessions().first().isEmpty())
        assertEquals(0, store.writes)
    }

    @Test fun `first send saves the current draft settings in the initial row`() = runBlocking {
        val store = SessionStore()
        val repository = ChatRepository(store.dao)
        val session = repository.createSession("deepseek-flash", memoryEnabled = false, thinkingOverride = "MAX")
        // No follow-up update is needed to preserve preferences across process death.
        assertEquals(1, store.writes)
        val restored = ChatRepository(store.dao).getSession(session.id)!!
        assertEquals("MAX", restored.thinkingOverride)
        assertEquals(0, restored.memoryEnabled)
        assertEquals(1, store.rows.size)
    }

    @Test fun `settings on a real session persist without creating another session`() = runBlocking {
        val store = SessionStore()
        val repository = ChatRepository(store.dao)
        val session = repository.createSession("deepseek-flash", thinkingOverride = "HIGH")
        repository.updateSessionThinkingOverride(session.id, "OFF")
        repository.updateSessionMemoryEnabled(session.id, false)
        val restored = ChatRepository(store.dao).getSession(session.id)!!
        assertEquals("OFF", restored.thinkingOverride)
        assertEquals(0, restored.memoryEnabled)
        assertEquals(1, store.rows.size)
    }
}
