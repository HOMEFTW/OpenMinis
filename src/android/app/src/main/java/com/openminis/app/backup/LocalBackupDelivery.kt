package com.openminis.app.backup

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller supplies the SAF stream and runs this on IO. A failed open/copy never reports success. */
internal suspend fun copyLocalBackup(source: File, backupRoot: File, openOutput: () -> OutputStream?): Long {
    val file = source.canonicalFile
    require(file.parentFile == backupRoot.canonicalFile && file.isFile) { "Backup file is no longer available." }
    return file.inputStream().use { input ->
        (openOutput() ?: error("Could not open the destination for writing.")).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
                copied += size
            }
            output.flush()
            copied
        }
    }
}
