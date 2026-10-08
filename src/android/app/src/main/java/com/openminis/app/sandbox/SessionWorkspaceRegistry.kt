package com.openminis.app.sandbox

import java.util.concurrent.ConcurrentHashMap

/** Shell identity stays the child; files can belong to its delegating parent. */
object SessionWorkspaceRegistry {
    private val owners = ConcurrentHashMap<String, String>()

    /** Restore ancestors too, before a producer registers another child of an unloaded parent. */
    suspend fun restore(sessionId: String, parentOf: suspend (String) -> String?): String {
        val path = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = sessionId
        while (true) {
            require(current.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session identifier" }
            require(seen.add(current)) { "Workspace ownership cycle" }
            path += current
            current = parentOf(current) ?: break
        }
        val root = path.last()
        synchronized(this) {
            owners.remove(root)
            path.dropLast(1).forEach { owners[it] = root }
        }
        return root
    }

    @Synchronized
    fun register(sessionId: String, ownerSessionId: String) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        require(ownerSessionId.matches(Regex("[A-Za-z0-9_-]+")))
        if (sessionId == ownerSessionId) owners.remove(sessionId)
        else {
            require(owner(ownerSessionId) != sessionId) { "Workspace ownership cycle" }
            owners[sessionId] = ownerSessionId
        }
    }

    @Synchronized
    fun owner(sessionId: String): String {
        var current = sessionId
        val seen = mutableSetOf<String>()
        while (seen.add(current)) current = owners[current] ?: return current
        return sessionId
    }
    @Synchronized
    fun forget(sessionId: String) { owners.remove(sessionId) }
}
