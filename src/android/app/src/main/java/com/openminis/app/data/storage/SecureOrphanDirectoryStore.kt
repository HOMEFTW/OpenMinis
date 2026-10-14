package com.openminis.app.data.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Requires handle-relative openat/unlinkat operations; never falls back to path-based deletion. */
internal class SecureOrphanDirectoryStore(private val filesDir: File) : StorageOrphanFiles {
    private data class Stamp(
        val key: Any,
        val created: FileTime,
        val modified: FileTime,
        val directory: Boolean,
        val size: Long,
    ) {
        // Android Unix attributes substitute mtime for unavailable birth time. Our own
        // child unlinks change directory mtime; only device/inode and type are identity.
        fun sameIdentity(other: Stamp) = key == other.key && directory == other.directory
    }

    private data class Tree(val stamp: Stamp, val children: Map<String, Tree> = emptyMap()) {
        val bytes: Long get() = if (stamp.directory) children.values.sumOf { it.bytes } else stamp.size
    }

    private data class Target(
        override val sessionId: String,
        override val media: Boolean,
        val names: List<String>,
        val parents: List<Stamp>,
        val tree: Tree,
    ) : StorageOrphanFiles.Target {
        override val bytes: Long get() = tree.bytes
    }

    private class UnsafeDirectory : IOException("Session directory identity is not safe to maintain")

    private fun stamp(attrs: BasicFileAttributes): Stamp {
        if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) throw UnsafeDirectory()
        return Stamp(attrs.fileKey() ?: throw UnsafeDirectory(), attrs.creationTime(), attrs.lastModifiedTime(),
            attrs.isDirectory, if (attrs.isRegularFile) attrs.size() else 0)
    }

    private fun name(value: String): Path {
        require(value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value)
        // Entries and all relative names use the same provider as the pinned directory.
        return filesDir.toPath().fileSystem.getPath(value)
    }

    private fun self(stream: SecureDirectoryStream<Path>): Stamp =
        stamp(stream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes())

    private fun child(stream: SecureDirectoryStream<Path>, value: String): Stamp = stamp(
        stream.getFileAttributeView(name(value), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
            .readAttributes(),
    )

    private fun open(stream: SecureDirectoryStream<Path>, value: String, expected: Stamp): SecureDirectoryStream<Path> {
        if (!expected.directory) throw UnsafeDirectory()
        val opened = stream.newDirectoryStream(name(value), NOFOLLOW_LINKS)
        try {
            if (!expected.sameIdentity(self(opened))) throw UnsafeDirectory()
            return opened
        } catch (e: Exception) {
            opened.close()
            throw e
        }
    }

    private fun anchor(): SecureDirectoryStream<Path> {
        val raw = filesDir.absoluteFile.toPath()
        val expected = stamp(Files.readAttributes(raw, BasicFileAttributes::class.java, NOFOLLOW_LINKS))
        if (!expected.directory) throw UnsafeDirectory()
        val actual = raw.toRealPath()
        val opened = Files.newDirectoryStream(actual)
        if (opened !is SecureDirectoryStream<Path>) {
            opened.close()
            throw IOException("SecureDirectoryStream is unavailable; session files were kept")
        }
        try {
            if (!expected.sameIdentity(self(opened)) ||
                !expected.sameIdentity(stamp(Files.readAttributes(raw, BasicFileAttributes::class.java, NOFOLLOW_LINKS)))) {
                throw UnsafeDirectory()
            }
            return opened
        } catch (e: Exception) {
            opened.close()
            throw e
        }
    }

    private suspend fun tree(stream: SecureDirectoryStream<Path>, depth: Int = 0, budget: IntArray = intArrayOf(100_000)): Tree {
        currentCoroutineContext().ensureActive()
        if (depth > 128 || --budget[0] < 0) throw UnsafeDirectory()
        val root = self(stream)
        val entries = linkedMapOf<String, Tree>()
        for (entry in stream) {
            currentCoroutineContext().ensureActive()
            if (--budget[0] < 0) throw UnsafeDirectory()
            val value = entry.fileName.toString()
            val attr = child(stream, value)
            entries[value] = if (attr.directory) open(stream, value, attr).use { tree(it, depth + 1, budget) }
                else Tree(attr)
        }
        // A directory changed during enumeration is not a stable confirmation snapshot.
        if (root != self(stream)) throw UnsafeDirectory()
        return Tree(root, entries)
    }

    override suspend fun scan(protection: StorageProtection): StorageOrphanPlan {
        val targets = mutableListOf<StorageOrphanFiles.Target>()
        var kept = 0
        anchor().use { anchor ->
            val root = self(anchor)
            suspend fun candidates(parent: SecureDirectoryStream<Path>, path: List<String>, parents: List<Stamp>, media: Boolean) {
                for (entry in parent) {
                    currentCoroutineContext().ensureActive()
                    val id = entry.fileName.toString()
                    if (!SessionStorageReferences.isIdentifier(id)) continue
                    if (protection.protects(id, media)) continue
                    try {
                        val attr = child(parent, id)
                        val snapshot = open(parent, id, attr).use { tree(it) }
                        targets += Target(id, media, path + id, parents, snapshot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        kept++
                    }
                }
            }
            suspend fun descend(parent: SecureDirectoryStream<Path>, value: String,
                                block: suspend (SecureDirectoryStream<Path>, Stamp) -> Unit) {
                try {
                    val attr = child(parent, value)
                    open(parent, value, attr).use { block(it, attr) }
                } catch (_: NoSuchFileException) {
                    // Layout roots are created lazily. Absence is not an orphan.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    kept++
                }
            }
            descend(anchor, "minis-sessions") { sessions, attr ->
                candidates(sessions, listOf("minis-sessions"), listOf(root, attr), false)
            }
            descend(anchor, "media") { media, mediaAttr ->
                if (SessionStorageReferences.ALL_MEDIA in protection.sessionIds) return@descend
                descend(media, "branches") { branches, attr ->
                    candidates(branches, listOf("media", "branches"), listOf(root, mediaAttr, attr), true)
                }
                for (yearEntry in media) {
                    val year = yearEntry.fileName.toString()
                    if (!year.matches(Regex("[0-9]{4}"))) continue
                    descend(media, year) { years, yearAttr ->
                        for (monthEntry in years) {
                            val month = monthEntry.fileName.toString()
                            if (!month.matches(Regex("[0-9]{2}"))) continue
                            descend(years, month) { months, monthAttr ->
                                for (dayEntry in months) {
                                    val day = dayEntry.fileName.toString()
                                    if (!SessionStorageReferences.validDate(year, month, day)) continue
                                    descend(months, day) { days, dayAttr ->
                                        candidates(days, listOf("media", year, month, day),
                                            listOf(root, mediaAttr, yearAttr, monthAttr, dayAttr), true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return StorageOrphanPlan(targets.toList(), kept)
    }

    override suspend fun delete(target: StorageOrphanFiles.Target, onFileDeleted: (Long) -> Unit): Boolean {
        require(target is Target) { "Cleanup target did not come from this directory scanner" }
        anchor().use { anchor ->
            if (!target.parents.first().sameIdentity(self(anchor))) return false
            val guards = mutableListOf<() -> Unit>()
            suspend fun remove(parent: SecureDirectoryStream<Path>, value: String, expected: Tree) {
                currentCoroutineContext().ensureActive()
                guards.forEach { it() }
                if (child(parent, value) != expected.stamp) throw UnsafeDirectory()
                if (expected.stamp.directory) {
                    open(parent, value, expected.stamp).use { directory ->
                        val guard = { if (!expected.stamp.sameIdentity(child(parent, value))) throw UnsafeDirectory() }
                        guards += guard
                        try {
                            for ((filename, subtree) in expected.children) remove(directory, filename, subtree)
                            currentCoroutineContext().ensureActive()
                            guards.forEach { it() }
                            parent.deleteDirectory(name(value))
                        } finally { guards.removeAt(guards.lastIndex) }
                    }
                } else {
                    parent.deleteFile(name(value))
                    onFileDeleted(expected.stamp.size)
                }
            }
            suspend fun reach(parent: SecureDirectoryStream<Path>, index: Int): Boolean {
                currentCoroutineContext().ensureActive()
                val value = target.names[index]
                if (index == target.names.lastIndex) {
                    try {
                        if (child(parent, value) != target.tree.stamp) return false
                        open(parent, value, target.tree.stamp).use { directory ->
                            if (tree(directory) != target.tree) return false
                        }
                    } catch (_: UnsafeDirectory) { return false }
                    catch (_: NoSuchFileException) { return false }
                    remove(parent, value, target.tree)
                    return true
                }
                val expected = target.parents[index + 1]
                val directory = try {
                    if (!expected.sameIdentity(child(parent, value))) return false
                    open(parent, value, expected)
                } catch (_: UnsafeDirectory) { return false }
                catch (_: NoSuchFileException) { return false }
                directory.use {
                    guards += { if (!expected.sameIdentity(child(parent, value))) throw UnsafeDirectory() }
                    try { return reach(it, index + 1) }
                    finally { guards.removeAt(guards.lastIndex) }
                }
            }
            return reach(anchor, 0)
        }
    }
}
