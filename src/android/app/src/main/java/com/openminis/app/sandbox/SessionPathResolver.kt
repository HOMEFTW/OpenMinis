package com.openminis.app.sandbox

import java.io.File

/** Resolves session-private paths without consulting the mutable global mount table. */
internal object SessionPathResolver {
    fun resolve(filesDir: File, linuxPath: String, sessionId: String?): File? {
        if (sessionId == null) return null
        val match = Regex("^/var/minis/(attachments|offloads|workspace|browser)(/.*)?$").matchEntire(linuxPath)
            ?: return null
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session identifier" }
        val session = File(filesDir.canonicalFile, "minis-sessions/$sessionId")
        require(session.canonicalFile == session.absoluteFile) { "Session directory is a symbolic link" }
        val scoped = File(session, match.groupValues[1])
        require(scoped.canonicalFile == scoped.absoluteFile) { "Session scope is a symbolic link" }
        val root = scoped.canonicalFile
        val target = File(root, match.groupValues[2].removePrefix("/")).canonicalFile
        require(target == root || target.toPath().startsWith(root.toPath())) { "Path escapes the session directory" }
        return target
    }
}
