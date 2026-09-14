package com.openminis.app.ui.chat

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Give copied media references independent files so deleting the source cannot break the branch. */
internal class EditBranchMedia(private val mediaRoot: File, branchId: String) {
    val directory = File(mediaRoot, "branches/$branchId")
    private val copies = mutableMapOf<String, String>()
    init {
        require(branchId.matches(Regex("[A-Za-z0-9_-]+")))
        require(directory.canonicalFile.toPath().startsWith(mediaRoot.canonicalFile.toPath()) && directory.canonicalFile == directory.absoluteFile)
    }
    fun rewrite(parts: String): String {
        val array = JSONArray(parts)
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("type") == "mediaRef") {
                        val ref = value.optJSONObject("value")
                        val path = ref?.optString("relativePath").orEmpty()
                        if (path.isNotBlank()) {
                            val copied = copies.getOrPut(path) {
                                val source = File(mediaRoot, path)
                                require(source.canonicalFile.toPath().startsWith(mediaRoot.canonicalFile.toPath()) && source.canonicalFile == source.absoluteFile)
                                require(source.isFile) { "Referenced media is missing" }
                                directory.mkdirs()
                                val destination = File(directory, UUID.randomUUID().toString() + "." + source.extension.ifBlank { "bin" })
                                source.copyTo(destination)
                                destination.relativeTo(mediaRoot).invariantSeparatorsPath
                            }
                            ref?.put("relativePath", copied)
                        }
                    }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(array)
        return array.toString()
    }
}
