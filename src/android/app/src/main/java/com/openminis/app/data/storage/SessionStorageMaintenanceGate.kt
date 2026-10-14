package com.openminis.app.data.storage

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short reservations protect files without holding a lock for the duration of a run. */
internal class SessionStorageMaintenanceGate {
    private val monitor = Any()
    private val reservations = mutableMapOf<String, Int>()
    private val deleting = mutableSetOf<String>()
    private val changed = MutableStateFlow(0L)
    private val maintenance = Mutex()

    /** Never waits for file IO; a synchronous caller can retry from a coroutine. */
    fun reserve(sessionIds: Set<String>): Closeable = synchronized(monitor) {
        validate(sessionIds)
        if (sessionIds.any { it in deleting }) throw StorageMaintenanceBusyException()
        reserveLocked(sessionIds.toSet())
    }

    suspend fun acquire(sessionIds: Set<String>): Closeable {
        validate(sessionIds)
        val ids = sessionIds.toSet()
        while (true) {
            currentCoroutineContext().ensureActive()
            val (lease, version) = synchronized(monitor) {
                if (ids.none { it in deleting }) reserveLocked(ids) to changed.value
                else null to changed.value
            }
            if (lease != null) return lease
            changed.first { it != version }
        }
    }

    suspend fun <T> withSessionActivity(sessionIds: Set<String>, block: suspend () -> T): T {
        val lease = acquire(sessionIds)
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            lease.close()
        }
    }

    fun <T> withSynchronousActivity(sessionIds: Set<String>, block: () -> T): T =
        reserve(sessionIds).use { block() }

    /** Callers doing backup work must acquire backupActivityLock BEFORE this gate. */
    suspend fun <T> withOrphanMaintenance(block: suspend (Maintenance) -> T): T =
        maintenance.withLock {
            val scope = Maintenance()
            try {
                block(scope)
            } finally {
                scope.close()
            }
        }

    fun reservedSessionIds(): Set<String> = synchronized(monitor) { reservations.keys.toSet() }

    private fun reserveLocked(ids: Set<String>): Closeable {
        ids.forEach { reservations[it] = (reservations[it] ?: 0) + 1 }
        var closed = false
        return Closeable {
            synchronized(monitor) {
                if (!closed) {
                    closed = true
                    ids.forEach { id ->
                        val count = checkNotNull(reservations[id])
                        if (count == 1) reservations.remove(id) else reservations[id] = count - 1
                    }
                    changed.value++
                }
            }
        }
    }

    inner class Maintenance internal constructor() : Closeable {
        private var open = true
        private val claims = mutableSetOf<String>()

        /** Must enclose fresh owner/reference checks as well as the file deletion. */
        fun claimIfUnreserved(sessionId: String, media: Boolean = false): Closeable? = synchronized(monitor) {
            check(open)
            validate(setOf(sessionId))
            val ids = setOf(sessionId, if (media) SessionStorageReferences.ALL_MEDIA else SessionStorageReferences.ALL_WORKSPACES)
            if (sessionId.startsWith("__new__") || ids.any { it in reservations || it in deleting }) {
                return@synchronized null
            }
            deleting += ids
            claims += ids
            var closed = false
            Closeable {
                synchronized(monitor) {
                    if (!closed) {
                        closed = true
                        ids.forEach { if (claims.remove(it)) deleting.remove(it) }
                        changed.value++
                    }
                }
            }
        }

        override fun close() = synchronized(monitor) {
            if (open) {
                open = false
                deleting.removeAll(claims)
                claims.clear()
                changed.value++
            }
        }
    }

    companion object {
        val shared = SessionStorageMaintenanceGate()

        private fun validate(ids: Set<String>) {
            require(ids.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Invalid session identifier" }
        }
    }
}

internal class StorageMaintenanceBusyException : IOException("Session files are being maintained")
