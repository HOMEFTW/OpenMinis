package com.openminis.app.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity

private const val READ_CHUNK_CHARS = 64_000

internal suspend fun <T> cursorWindowFallback(read: suspend () -> T, fallback: suspend () -> T): T =
    try {
        read()
    } catch (e: SQLiteBlobTooBigException) {
        fallback()
    } catch (e: IllegalStateException) {
        if (e.message?.contains("CursorWindow", ignoreCase = true) == true) fallback() else throw e
    }

/** A failed row is reconstructed in small SQLite substr reads, never skipped. */
internal suspend fun readMessagePageSafely(
    dao: ChatDao,
    sessionId: String,
    offset: Int,
    limit: Int,
    startMs: Long? = null,
    endMs: Long? = null,
    readPage: suspend (Int, Int) -> List<MessageEntity>,
): List<MessageEntity> = cursorWindowFallback(
    { readPage(offset, limit) },
    {
        val rows = mutableListOf<MessageEntity>()
        for (index in offset until offset + limit) {
            val row = cursorWindowFallback(
                { readPage(index, 1).firstOrNull() },
                {
                    dao.loadMessageMetadataPage(sessionId, index, 1, startMs, endMs).firstOrNull()
                        ?.let { readMessageFromChunks(dao, it) }
                },
            ) ?: break
            rows.add(row)
        }
        rows
    },
).map { it.withReadableAssistantParts() }

internal suspend fun readMessageFromChunks(dao: ChatDao, meta: MessageEntity): MessageEntity = meta.copy(
    partsJson = requireNotNull(readMessageColumnChunks(dao, meta.id, false)) {
        "Message disappeared during chunked read"
    },
    reasoningContent = readMessageColumnChunks(dao, meta.id, true),
).withReadableAssistantParts()

private suspend fun readMessageColumnChunks(dao: ChatDao, id: String, reasoning: Boolean): String? {
    val result = StringBuilder()
    var offset = 1
    while (true) {
        val chunk = dao.loadMessageColumnChunk(id, reasoning, offset, READ_CHUNK_CHARS) ?: return null
        if (chunk.isEmpty()) break
        result.append(chunk)
        // SQLite substr offsets count Unicode code points, not Kotlin UTF-16 units.
        val chars = chunk.codePointCount(0, chunk.length)
        offset += chars
        if (chars < READ_CHUNK_CHARS) break
    }
    return result.toString()
}
