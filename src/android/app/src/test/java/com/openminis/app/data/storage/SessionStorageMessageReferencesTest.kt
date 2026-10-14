package com.openminis.app.data.storage

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionStorageMessageReferencesTest {
    @Test fun manyHistoriesAreReadAsSmallIdPagesAndOneBodyAtATime() = runBlocking {
        val rows = (0 until 100).associate { "message-${it.toString().padStart(3, '0')}" to
            """[{"type":"text","value":{"text":"\/private\/\u006dinis-sessions\/source-${it % 3}\/file"}}]""" }.toSortedMap()
        var idPages = 0
        var bodies = 0
        val protected = SessionStorageMessageReferences.collect { sql, args ->
            if (sql.startsWith("SELECT id AS")) {
                idPages++
                assertTrue(sql.endsWith("LIMIT 16"))
                rows.keys.filter { args.isEmpty() || it > args[0] as String }.take(16)
            } else {
                bodies++
                assertEquals("SELECT parts_json FROM messages WHERE id = ?", sql)
                listOfNotNull(rows[args.single()])
            }
        }
        assertEquals(setOf("source-0", "source-1", "source-2"), protected)
        assertEquals(100, bodies)
        assertEquals(8, idPages)
    }

    @Test fun deletedPageRowDoesNotSkipTheFollowingReferenceAndQueryErrorsAbort() = runBlocking {
        var pages = 0
        val protected = SessionStorageMessageReferences.collect { sql, args ->
            when {
                sql.startsWith("SELECT id AS") -> if (pages++ == 0) listOf("a", "b") else emptyList()
                args.single() == "a" -> emptyList()
                else -> listOf("""[{"type":"text","value":{"text":"/private/minis-sessions/retained/file"}}]""")
            }
        }
        assertEquals(setOf("retained"), protected)
        assertTrue(runCatching {
            SessionStorageMessageReferences.collect { sql, _ ->
                if (sql.startsWith("SELECT id AS")) listOf("a") else throw IOException("Cursor cannot read body")
            }
        }.isFailure)
    }
}
