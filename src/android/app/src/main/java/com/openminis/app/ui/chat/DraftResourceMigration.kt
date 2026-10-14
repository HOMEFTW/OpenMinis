package com.openminis.app.ui.chat

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.openminis.app.data.storage.SessionStorageMaintenanceGate
import com.openminis.app.data.storage.SessionStorageReferences

/** Move resources without deleting a source that has not reached its destination. */
internal object DraftResourceMigration {
    fun migrate(source: File, destination: File): Boolean = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        SessionStorageReferences.idsForFiles(source, destination),
    ) { migrateReserved(source, destination) }

    private fun migrateReserved(source: File, destination: File): Boolean {
        if (!source.exists()) return true
        if (!source.isDirectory || (!destination.isDirectory && !destination.mkdirs())) return false
        var complete = true
        for (name in listOf("attachments", "offloads", "workspace", "browser")) {
            val child = File(source, name)
            if (child.exists() && !moveReserved(child, File(destination, name))) complete = false
        }
        // An unknown or unreadable entry must survive too. Never recursively
        // delete the draft merely because the known subdirectories were visited.
        if (complete) source.delete()
        return complete && !source.exists()
    }

    fun move(source: File, destination: File): Boolean = SessionStorageMaintenanceGate.shared.withSynchronousActivity(
        SessionStorageReferences.idsForFiles(source, destination),
    ) { moveReserved(source, destination) }

    private fun moveReserved(source: File, destination: File): Boolean = runCatching {
        if (!source.exists()) return@runCatching true
        val parent = destination.parentFile ?: return@runCatching false
        if (!parent.isDirectory && !parent.mkdirs()) return@runCatching false
        if (!destination.exists() && source.renameTo(destination)) return@runCatching true
        if (source.isDirectory) {
            if (!destination.isDirectory && !destination.mkdirs()) return@runCatching false
            val children = source.listFiles() ?: return@runCatching false
            var complete = true
            children.forEach { if (!moveReserved(it, File(destination, it.name))) complete = false }
            complete && source.delete()
        } else {
            if (destination.exists()) {
                // A previous copy may have succeeded while source deletion
                // failed. Verify bytes before acknowledging that retry.
                if (!destination.isFile || source.length() != destination.length() ||
                    !digest(source).contentEquals(digest(destination))) return@runCatching false
            } else {
                val staged = File(parent, ".draft-${UUID.randomUUID()}.tmp")
                try {
                    source.copyTo(staged)
                    if (!staged.renameTo(destination)) return@runCatching false
                } finally {
                    staged.delete()
                }
            }
            source.delete()
        }
    }.getOrDefault(false)

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
