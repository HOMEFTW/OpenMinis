package com.openminis.app.ui.chat

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class TaskArtifact(val file: File, val relativePath: String, val size: Long, val modified: Long)
internal data class TaskArtifactListing(val files: List<TaskArtifact>, val limited: Boolean)

/** Bounded scan of this session only; never follow links into other sessions or mounts. */
internal object TaskArtifacts {
    fun list(filesDir: File, sessionId: String): TaskArtifactListing {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(filesDir.canonicalFile, "minis-sessions/$sessionId")
        require(directory.canonicalFile == directory.absoluteFile) { "Session directory is a symbolic link" }
        val session = directory.toPath()
        val result = mutableListOf<TaskArtifact>()
        var visited = 0
        var limited = false
        for (scope in listOf("attachments", "workspace")) {
            val root = session.resolve(scope)
            if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) continue
            Files.walkFileTree(root, emptySet(), 8, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited > 5000) { limited = true; return FileVisitResult.TERMINATE }
                    if (dir != root && (dir.fileName.toString().startsWith(".") ||
                        dir.fileName.toString() in setOf("node_modules", "build", "__pycache__"))) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited > 5000) { limited = true; return FileVisitResult.TERMINATE }
                    if (attrs.isDirectory) limited = true // depth limit
                    if (attrs.isRegularFile && !file.fileName.toString().startsWith(".") &&
                        file.toFile().canonicalFile.toPath().startsWith(session)) {
                        result.add(TaskArtifact(file.toFile(), session.relativize(file).toString(), attrs.size(), attrs.lastModifiedTime().toMillis()))
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    limited = true
                    return FileVisitResult.CONTINUE
                }
            })
            if (visited > 5000) break
        }
        if (result.size > 500) limited = true
        return TaskArtifactListing(result.sortedWith(compareByDescending<TaskArtifact> { it.modified }.thenBy { it.relativePath }).take(500), limited)
    }
}
