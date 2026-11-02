package com.openminis.app.forge

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Private app storage only. Never accepts a host path from a bridge request. */
internal class ForgeLocalFiles(directory: File) {
    val root: File
    val lock: Any

    init {
        require(!Files.isSymbolicLink(directory.toPath())) { "Forge directory cannot be a symbolic link" }
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create Forge directory" }
        root = directory.canonicalFile
        lock = locks.computeIfAbsent(root.path) { Any() }
    }

    fun file(relative: String): File {
        validPath(relative)
        var cursor = root
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            require(!Files.isSymbolicLink(cursor.toPath())) { "Forge symbolic links are not allowed" }
        }
        require(cursor.canonicalFile.toPath().startsWith(root.toPath())) { "Forge path escapes storage" }
        return cursor
    }

    fun read(relative: String, maximum: Int): ByteArray {
        val target = file(relative)
        require(target.isFile && target.length() <= maximum) { "Invalid or oversized Forge file" }
        return FileInputStream(target).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= maximum) { "Forge file limit exceeded" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
    }

    fun write(relative: String, bytes: ByteArray, maximum: Int) {
        require(bytes.size <= maximum) { "Forge file limit exceeded" }
        val target = file(relative)
        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create Forge directory" }
        file(relative)
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        Files.createFile(temporary.toPath())
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            file(relative)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        fun validPath(path: String): String {
            require(path.length in 1..1000 && path.none { it in "\\:\u0000\r\n" } &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid skill resource path" }
            return path
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal fun forgeInteger(args: JSONObject, key: String, default: Long? = null, maximum: Long = 9_007_199_254_740_991L): Long {
    val value = if (args.has(key)) args.opt(key) else default
    require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() &&
        value.toLong() in 0..maximum) { "Invalid $key" }
    return value.toLong()
}

/** Only the authenticated host supplies trustedSession. Request fields cannot select another chat. */
internal fun forgeSession(args: JSONObject, trustedSession: String?, allowPersonal: Boolean = true): String? {
    val scope = args.opt("scope")
    require(scope == null || scope == "conversation" || scope == "session" || (allowPersonal && scope == "personal") || scope == "global") { "Invalid scope" }
    val selectors = listOf("threadId", "sessionId").filter { args.has(it) && !args.isNull(it) }.map { key ->
        (args.opt(key) as? String)?.also { require(it.length in 1..256 && it.none { ch -> ch == '\u0000' || ch == '\r' || ch == '\n' }) { "Invalid session ID" } }
            ?: throw IllegalArgumentException("Invalid session ID")
    }
    val personal = allowPersonal && (scope == "personal" || (scope == null && selectors.contains("personal")))
    if (personal) {
        require(selectors.all { it == "personal" }) { "Session ID does not match personal scope" }
        return null
    }
    require(!trustedSession.isNullOrBlank() && trustedSession.length <= 256) { "Open a conversation before using conversation tools" }
    require(selectors.all { it == trustedSession || it == "current" || it == "conversation" }) { "Session ID does not match the current conversation" }
    return trustedSession
}
