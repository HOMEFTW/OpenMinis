package com.openminis.app.forge

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object ForgeStorage {
    fun id(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid ID" } }
    fun write(file: File, value: JSONObject, limit: Int = 12 * 1024 * 1024) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "Document too large" }
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun read(file: File, limit: Long = 12L * 1024 * 1024): JSONObject {
        require(file.isFile && file.length() <= limit) { "Document missing or too large" }
        return JSONObject(file.readText())
    }
}
