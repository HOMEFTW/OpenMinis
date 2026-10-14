package com.openminis.app.data.storage

import com.openminis.app.data.repository.readablePartsJson
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Owner hints are conservative: unknown media layouts reserve the entire media root. */
internal object SessionStorageReferences {
    const val ALL_MEDIA = "__storage_media__"
    const val ALL_WORKSPACES = "__storage_workspaces__"
    private val identifier = Regex("[A-Za-z0-9_-]+")
    private val workspacePath = Regex("(?:^|[/\\\\])minis-sessions[/\\\\]([A-Za-z0-9_-]+)(?:[/\\\\]|$)")
    private val mediaPath = Regex("(?:^|/)media/((?:[0-9]{4}/[0-9]{2}/[0-9]{2}|branches)/[A-Za-z0-9_-]+)(?:/|$)")

    fun isIdentifier(id: String): Boolean = identifier.matches(id)

    fun mediaOwner(relativePath: String): String? {
        val parts = relativePath.split('/')
        require(relativePath.isNotBlank() && '\\' !in relativePath &&
            parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid media reference" }
        val id = when {
            parts.size >= 2 && parts[0] == "branches" -> parts[1]
            parts.size >= 4 && validDate(parts[0], parts[1], parts[2]) -> parts[3]
            else -> return null
        }
        return id.takeIf(::isIdentifier)
    }

    fun validDate(year: String, month: String, day: String): Boolean {
        if (!year.matches(Regex("[0-9]{4}")) || !month.matches(Regex("[0-9]{2}")) ||
            !day.matches(Regex("[0-9]{2}"))) return false
        return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.isSuccess
    }

    /** Malformed saved data must abort the safety check, not disappear from its owner set. */
    private fun visitParts(partsJson: String, onMedia: (String) -> Unit, onText: (String) -> Unit) {
        fun visit(value: JsonElement, depth: Int = 0, structuredInput: Boolean = false) {
            require(depth < 128) { "Message references are too deeply nested" }
            when (value) {
                is JsonObject -> {
                    if ((value["type"] as? JsonPrimitive)?.content == "mediaRef") {
                        val ref = requireNotNull(value["value"] as? JsonObject) { "Unreadable media reference" }
                        val pathValue = requireNotNull(ref["relativePath"] as? JsonPrimitive) { "Missing media path" }
                        require(pathValue.isString) { "Unreadable media path" }
                        val path = pathValue.content
                        mediaOwner(path) // Validate traversal even for an unknown layout.
                        onMedia(path)
                    }
                    value.forEach { (key, item) -> visit(item, depth + 1, key == "input") }
                }
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content
                    onText(text.replace("\\/", "/"))
                    // Tool input is sometimes JSON inside a JSON string. Decode escaped
                    // slash/unicode again; damaged structured data must fail closed.
                    if (structuredInput && text.trimStart().firstOrNull() in listOf('{', '[')) {
                        visit(Json.parseToJsonElement(text), depth + 1)
                    }
                }
            }
        }
        val readable = readablePartsJson(partsJson)
        val parsed = Json.parseToJsonElement(readable)
        require(parsed is JsonArray) { "Unreadable message parts" }
        visit(parsed)
    }

    fun mediaPaths(partsJson: String): Set<String> = linkedSetOf<String>().apply {
        visitParts(partsJson, ::add) {}
    }

    fun idsForParts(partsJson: String): Set<String> = linkedSetOf<String>().apply {
        visitParts(partsJson, { add(mediaOwner(it) ?: ALL_MEDIA) }, { addAll(idsInText(it)) })
    }

    /** Streaming tool stubs can have damaged input. Preserve the record and reserve all roots. */
    fun idsForWrite(partsJson: String): Set<String> = try {
        idsForParts(partsJson)
    } catch (_: Exception) {
        setOf(ALL_WORKSPACES, ALL_MEDIA)
    }

    fun idsInText(text: String): Set<String> =
        workspacePath.findAll(text).map { it.groupValues[1] }.toSet() +
            mediaPath.findAll(text.replace('\\', '/')).map { mediaOwner(it.groupValues[1]) ?: ALL_MEDIA }.toSet()

    /** Lexical only; reservation registration must not do filesystem IO on Main. */
    fun idsForFiles(vararg files: File): Set<String> = files.flatMap { file ->
        val chain = generateSequence(file.absoluteFile) { it.parentFile }.toList()
        val session = chain.firstOrNull { it.parentFile?.name == "minis-sessions" }
        when {
            session != null -> listOf(session.name.also { require(isIdentifier(it)) })
            file.parentFile?.name == "browser_tabs" ->
                listOf(file.name.removeSuffix(".json").also { require(isIdentifier(it)) })
            else -> idsInText(file.absolutePath.replace('\\', '/')).toList()
        }
    }.toSet()
}
