package com.openminis.app.data.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keyset pages of IDs, one body at a time; never materialize the database's full history. */
internal object SessionStorageMessageReferences {
    suspend fun collect(query: suspend (String, Array<Any>) -> List<String>): Set<String> {
        val retained = linkedSetOf<String>()
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = if (after == null) query("SELECT id AS parts_json FROM messages ORDER BY id LIMIT 16", emptyArray())
            else query("SELECT id AS parts_json FROM messages WHERE id > ? ORDER BY id LIMIT 16", arrayOf(after))
            if (ids.isEmpty()) return retained
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                require(SessionStorageReferences.isIdentifier(id) && (after == null || id > after)) {
                    "Unreadable message reference cursor"
                }
                val body = query("SELECT parts_json FROM messages WHERE id = ?", arrayOf(id))
                require(body.size <= 1) { "Ambiguous message reference" }
                // A row deleted since its ID page no longer holds references. SQL/JSON
                // errors propagate, including an oversized row the CursorWindow cannot read.
                body.singleOrNull()?.let { retained += SessionStorageReferences.idsForParts(it) }
                after = id
            }
        }
    }
}
