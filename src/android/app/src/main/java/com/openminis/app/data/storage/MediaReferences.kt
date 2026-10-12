package com.openminis.app.data.storage

import com.openminis.app.data.repository.readablePartsJson
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Walk all nested media parts, including tool results and text/plain pasted content. */
object MediaReferences {
    fun paths(parts: String): Set<String> = linkedSetOf<String>().apply {
        rewrite(parts) { add(it); it }
    }

    fun rewrite(parts: String, copyPath: (String) -> String): String {
        val array = JSONArray(readablePartsJson(parts))
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("type") == "mediaRef") {
                        val ref = value.optJSONObject("value")
                        val path = ref?.optString("relativePath").orEmpty()
                        if (path.isNotBlank()) ref?.put("relativePath", copyPath(path))
                    }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(array)
        return array.toString()
    }

    /** References may select only ordinary paths inside media, never links or traversal. */
    fun file(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && '\\' !in relativePath &&
            relativePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid media path" }
        val base = root.canonicalFile
        val file = File(base, relativePath)
        require(file.canonicalFile.toPath().startsWith(base.toPath()) && file.canonicalFile == file.absoluteFile) {
            "Media path escapes its root or follows a link"
        }
        return file
    }
}
