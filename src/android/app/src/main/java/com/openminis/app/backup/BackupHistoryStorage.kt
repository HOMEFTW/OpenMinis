package com.openminis.app.backup

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A failed/aborted rewrite must leave the previous history readable. */
internal class BackupHistoryStorage(private val file: File) {
    fun read(): String? = file.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun write(body: (OutputStream) -> Unit) {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create backup history directory" }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                body(stream)
                stream.flush()
                stream.fd.sync()
            }
            // Same-directory replacement: never truncate the live JSON, and
            // never fall back to a copy that could publish a partial document.
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}
