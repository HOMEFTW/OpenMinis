package com.openminis.app.backup

import com.openminis.app.data.storage.MediaReferences
import java.io.File

/** Additional CHATS index entries; legacy chats/<sid> workspace paths stay unchanged. */
internal object BackupChatMedia {
    fun export(
        parts: String,
        mediaRoot: File,
        trees: BackupFileTreeExporter,
        exportedPaths: MutableSet<String>,
        sessionId: String,
    ): BackupFileTreeExporter.Result {
        val result = BackupFileTreeExporter.Result()
        for (path in MediaReferences.paths(parts)) {
            if (!exportedPaths.add(path)) continue
            val one = trees.exportFile(MediaReferences.file(mediaRoot, path), "media/$path", BackupCategory.CHATS, sessionId)
            result.filesIncluded += one.filesIncluded
            result.bytesIncluded += one.bytesIncluded
            result.filesSkipped += one.filesSkipped
            result.filesAfterSnapshot += one.filesAfterSnapshot
        }
        return result
    }

    fun restore(packageRoot: File, fileIndex: List<BackupFileIndexEntry>, filesDir: File): BackupRestoreFiles.Result {
        val mediaRoot = File(filesDir, "media")
        return BackupRestoreFiles.restore(packageRoot, fileIndex, BackupCategory.CHATS, mediaRoot,
            overwriteExisting = false) { path ->
            if (path.startsWith("media/")) File(mediaRoot, path.removePrefix("media/")) else null
        }
    }
}
