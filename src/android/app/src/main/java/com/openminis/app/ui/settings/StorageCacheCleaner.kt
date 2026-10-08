package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.data.session.SessionStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/**
 * [T-storage-clear-logs-caches] What "Logs & Caches" counts and clears.
 *
 * NOT all of cacheDir: this app keeps live state there that must survive —
 * `proot-tmp` (the running shell's /tmp), `pasted_text` (unsent drafts),
 * `share_inbound`, restore/import work dirs, the rootfs backup, rclone config.
 * So the set is an allowlist:
 *   * every log file (daily, crash, exit-info) via AppLogger.clearLogs(),
 *     which also drops the open writer so logging continues;
 *   * regenerable caches, refetched on demand (model lists, models.dev);
 *   * outbound share/export scratch, only entries untouched for an hour, so a
 *     share another app is still reading is not pulled out from under it.
 * The size shown is exactly what clear() removes.
 */
internal object LogsAndCachesCleaner {
    private val REGENERABLE = listOf("models-cache", "models-dev-cache")
    private val TRANSIENT = listOf("share", "shared", "export-staging", "large-messages")
    private const val TRANSIENT_MIN_AGE_MS = 60 * 60 * 1000L

    private fun transientEntries(cacheDir: File, now: Long): List<File> {
        val cutoff = now - TRANSIENT_MIN_AGE_MS
        return TRANSIENT.flatMap { name ->
            val root = File(cacheDir, name)
            if (Files.isSymbolicLink(root.toPath())) emptyList()
            else root.listFiles()?.filter { untouchedBefore(it, cutoff) } ?: emptyList()
        }
    }

    private fun untouchedBefore(root: File, cutoff: Long): Boolean {
        var untouched = true
        try {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    checkAge(attrs)
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
                    checkAge(attrs)
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    untouched = false
                    return FileVisitResult.TERMINATE
                }
                private fun checkAge(attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.lastModifiedTime().toMillis() >= cutoff) {
                        untouched = false
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {
            untouched = false
        }
        return untouched
    }

    /** A cache entry may be a link into another session; delete the link, never its target. */
    private fun deleteNoFollow(root: File) {
        runCatching {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    /** Clearable cache bytes under [cacheDir] (logs excluded). */
    fun cachesSize(cacheDir: File, now: Long = System.currentTimeMillis()): Long =
        REGENERABLE.sumOf { SessionStorage.directorySize(File(cacheDir, it)) } +
            transientEntries(cacheDir, now).sumOf { if (it.isDirectory) SessionStorage.directorySize(it) else it.length() }

    /** Delete the clearable caches under [cacheDir] (logs excluded). */
    fun clearCaches(cacheDir: File, now: Long = System.currentTimeMillis()) {
        REGENERABLE.forEach { deleteNoFollow(File(cacheDir, it)) }
        transientEntries(cacheDir, now).forEach { deleteNoFollow(it) }
    }

    fun size(context: Context): Long =
        com.openminis.app.logging.AppLogger.totalSize() + cachesSize(context.cacheDir)

    fun clear(context: Context) {
        com.openminis.app.logging.AppLogger.clearLogs()
        clearCaches(context.cacheDir)
    }
}
