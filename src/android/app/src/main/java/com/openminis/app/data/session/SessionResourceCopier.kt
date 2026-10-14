package com.openminis.app.data.session

import com.openminis.app.data.storage.MediaReferences
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import com.openminis.app.data.storage.SessionStorageReferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Copies resources without changing or deleting any source, for duplicates and edit branches. */
class SessionResourceCopier(private val filesDir: File, private val targetSessionId: String) {
    private val storage = SessionStorage(filesDir)
    val mediaDirectory = MediaReferences.file(storage.mediaRoot, "branches/$targetSessionId")
    private val copies = mutableMapOf<String, String>()

    init { storage.sessionDir(targetSessionId) }

    fun rewrite(parts: String): String = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        setOf(targetSessionId) + SessionStorageReferences.idsForWrite(parts),
    ) { MediaReferences.rewrite(parts, ::copyMedia) }

    private fun copyMedia(path: String): String = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        setOf(targetSessionId, SessionStorageReferences.mediaOwner(path) ?: SessionStorageReferences.ALL_MEDIA),
    ) {
        copies.getOrPut(path) {
            val source = MediaReferences.file(storage.mediaRoot, path)
            require(source.isFile) { "Referenced media is missing: $path" }
            if (!mediaDirectory.isDirectory && !mediaDirectory.mkdirs()) throw IOException("Cannot create media directory")
            val target = File(mediaDirectory, UUID.randomUUID().toString() + "." + source.extension.ifBlank { "bin" })
            source.copyTo(target)
            target.relativeTo(storage.mediaRoot.canonicalFile).invariantSeparatorsPath
        }
    }

    /** Stable Linux paths now resolve through the target session's own mount. */
    fun copyWorkspace(sourceSessionId: String, checkActive: () -> Unit = {}) =
        SessionStorageMaintenanceGate.shared.withSynchronousActivity(setOf(sourceSessionId, targetSessionId)) {
            copyWorkspaceReserved(sourceSessionId, checkActive)
        }

    private fun copyWorkspaceReserved(sourceSessionId: String, checkActive: () -> Unit) {
        require(sourceSessionId != targetSessionId)
        val sourceRoot = storage.sessionDir(sourceSessionId)
        val targetRoot = storage.sessionDir(targetSessionId)
        if (!sourceRoot.exists()) return
        fun copy(source: File, target: File) {
            checkActive()
            if (Files.isSymbolicLink(source.toPath())) {
                val originalLink = Files.readSymbolicLink(source.toPath())
                val resolved = runCatching { source.toPath().toRealPath() }.getOrElse {
                    // Linux-only and dangling links still need rebasing when
                    // their target belongs to this session.
                    requireNotNull(source.parentFile).toPath().resolve(originalLink).normalize()
                }
                val mapped = when {
                    resolved.startsWith(sourceRoot.toPath()) ->
                        targetRoot.toPath().resolve(sourceRoot.toPath().relativize(resolved))
                    resolved.startsWith(storage.mediaRoot.canonicalFile.toPath()) && source.isFile ->
                        File(storage.mediaRoot, copyMedia(storage.mediaRoot.canonicalFile.toPath().relativize(resolved).toString().replace(File.separatorChar, '/'))).toPath()
                    else -> null
                }
                // Keep global/Linux links as links, without following or
                // copying their external targets; rebase all session-owned links.
                val link = mapped?.let { requireNotNull(target.parentFile).toPath().relativize(it) }
                    ?: originalLink
                Files.createSymbolicLink(target.toPath(), link)
            } else if (source.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create ${target.name}")
                val children = source.listFiles() ?: throw IOException("Cannot read ${source.name}")
                children.forEach { copy(it, File(target, it.name)) }
            } else if (source.isFile) {
                source.copyTo(target, overwrite = false)
            } else {
                throw IOException("Unsupported workspace entry: ${source.name}")
            }
        }
        copy(sourceRoot, targetRoot)
    }

    fun cleanup() = SessionStorageMaintenanceGate.shared.withSynchronousActivity(setOf(targetSessionId)) {
        storage.deleteFiles(targetSessionId)
        Unit
    }
}
