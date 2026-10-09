package com.openminis.app.speech

import java.io.File

/** File sources stay inside an explicitly resolved sandbox mount, never a host path. */
internal object SpeechAudioFile {
    const val MAX_INPUT_BYTES = 25 * 1024 * 1024

    fun resolve(source: String, cwd: String, resolveRoot: (String) -> File?): File {
        val path = if (source.startsWith('/')) source else "${cwd.trimEnd('/')}/$source"
        require('\u0000' !in path && '\\' !in path) { "Invalid audio path" }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(".." !in parts) { "Audio path must not contain parent-directory traversal" }
        require(parts.size >= 4 && parts[0] == "var" && parts[1] == "minis" &&
            parts[2] in setOf("attachments", "offloads", "workspace", "browser", "shared")) {
            "Audio must be under /var/minis/{attachments,offloads,workspace,browser,shared}"
        }
        val root = requireNotNull(resolveRoot("/var/minis/${parts[2]}")) {
            "Audio mount is unavailable; a session is required for private paths"
        }.toPath().toRealPath()
        val candidate = root.resolve(parts.drop(3).joinToString("/")).toFile()
        require(candidate.isFile && candidate.canRead()) { "Audio source is not a readable regular file" }
        val actual = candidate.toPath().toRealPath()
        require(actual.startsWith(root) && actual != root) { "Audio path escapes its mount" }
        val file = actual.toFile()
        require(file.length() in 1..MAX_INPUT_BYTES.toLong()) { "Audio file must be between 1 byte and 25 MiB" }
        return file
    }
}
