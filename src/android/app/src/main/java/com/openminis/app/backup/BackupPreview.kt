package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal data class BackupPreview(val bytes: Long, val limited: Boolean)
internal fun backupIncludesCredentials(categories: Set<BackupCategory>): Boolean = categories.any {
    it == BackupCategory.PROVIDERS || it == BackupCategory.MCP_SERVERS || it == BackupCategory.ENVIRONMENT_VARIABLES
}

/** Estimate source bytes without reading secret values or following external links. */
internal suspend fun estimateBackup(context: Context, categories: Set<BackupCategory>, maxFileSizeMB: Int): BackupPreview {
    var bytes = if (BackupCategory.CHATS in categories) AppDatabase.getInstance(context).chatDao().estimateMessageBytes() else 0L
    var limited = false
    var visited = 0
    val roots = buildList {
        if (BackupCategory.CHATS in categories && maxFileSizeMB >= 0) add(File(context.filesDir, "minis-sessions"))
        if (BackupCategory.SHARED_FILES in categories && maxFileSizeMB >= 0) add(File(context.filesDir, "minis-global/shared"))
        if (BackupCategory.SKILLS in categories && maxFileSizeMB >= 0) add(File(context.filesDir, "minis-global/skills"))
        if (BackupCategory.MEMORY in categories) add(File(context.filesDir, "minis-global/memory"))
    }
    for (root in roots) {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) continue
        Files.walkFileTree(root.toPath(), emptySet(), 32, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++visited > 100_000) { limited = true; return FileVisitResult.TERMINATE }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++visited > 100_000) { limited = true; return FileVisitResult.TERMINATE }
                if (attrs.isDirectory) limited = true
                val capApplies = root.name != "memory"
                if (attrs.isRegularFile && (!capApplies || maxFileSizeMB <= 0 || attrs.size() <= maxFileSizeMB * 1024L * 1024)) bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                limited = true; return FileVisitResult.CONTINUE
            }
        })
        if (visited > 100_000) break
    }
    return BackupPreview(bytes, limited)
}
