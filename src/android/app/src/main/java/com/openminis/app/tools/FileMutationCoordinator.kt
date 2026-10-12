package com.openminis.app.tools

import java.io.File
import java.util.concurrent.locks.ReentrantLock

/** Serialize a complete mutation, including reads, across aliases of one file. */
internal object FileMutationCoordinator {
    private class Entry {
        val lock = ReentrantLock()
        var users = 0
    }
    private val entries = mutableMapOf<String, Entry>()

    fun <T> withFile(file: File, action: () -> T): T {
        val path = file.canonicalPath
        val entry = synchronized(entries) {
            entries.getOrPut(path, ::Entry).also { it.users++ }
        }
        var acquired = false
        try {
            entry.lock.lockInterruptibly()
            acquired = true
            return action()
        } finally {
            if (acquired) entry.lock.unlock()
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(path)
            }
        }
    }
}
