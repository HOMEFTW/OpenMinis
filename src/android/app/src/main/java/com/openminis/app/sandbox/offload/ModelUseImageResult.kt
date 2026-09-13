package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMResponse
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves generated media using the caller's session path resolver. */
internal fun saveModelUseResult(
    modelId: String,
    response: LLMResponse,
    outputPath: String?,
    outputExt: String,
    endpointUsed: String? = null,
    requireImage: Boolean = false,
    resolveFile: (String) -> File?,
    mimeToExt: (String) -> String,
    logWrite: (String, File) -> Unit = { _, _ -> },
): NativeOffloadResult {
    fun failure(message: String) = NativeOffloadResult(
        1, JSONObject().put("error", if (requireImage) "image_generation_failed" else "model_use_failed").put("message", message).toString() + "\n",
    )
    if (outputPath != null && !outputPath.startsWith("/")) {
        return failure("Output must be an absolute Linux path, not a relative path or file URI.")
    }
    val images = response.mediaAttachments
    if ((requireImage && images.isEmpty()) || images.any { it.data.isEmpty() }) {
        return failure("Image endpoint returned no usable image data.")
    }
    val outputIsMedia = outputExt in setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "wav", "mp3", "m4a", "aac", "ogg", "flac", "mp4", "mov", "webm", "mkv")
    if (outputPath != null && outputIsMedia && images.isEmpty()) return failure("Model returned no media data.")
    val outputFile = outputPath?.let {
        resolveFile(it) ?: return failure("Cannot resolve --output '$it'.")
    }
    // A JSON/text output is a manifest/description, never a substitute for the images.
    // Also keep every additional image when --output names the first image directly.
    val needsAttachments = images.isNotEmpty() && (outputFile == null || !outputIsMedia || images.size > 1)
    val attachmentDir = if (needsAttachments) {
        resolveFile("/var/minis/attachments")
            ?: return failure("Cannot resolve image attachments directory.")
    } else null
    val mediaFiles = JSONArray()
    val runId = java.util.UUID.randomUUID().toString()
    try {
        for ((index, media) in images.withIndex()) {
            val direct = outputFile != null && outputIsMedia && index == 0
            val fileName = "model-use-$runId-$index.${mimeToExt(media.mimeType).takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: "bin"}"
            val file = if (direct) outputFile!! else File(attachmentDir!!, fileName)
            val path = if (direct) outputPath!! else "/var/minis/attachments/$fileName"
            file.parentFile?.mkdirs()
            file.writeBytes(media.data)
            logWrite(path, file)
            mediaFiles.put(JSONObject().apply {
                put("type", media.type.value)
                put("mime_type", media.mimeType)
                put("path", path)
                put("size", media.data.size)
            })
        }
        val body = JSONObject().apply {
            put("model", modelId)
            put("text", response.text)
            if (endpointUsed != null) put("image_endpoint", endpointUsed)
            response.usage?.let { usage ->
                put("usage", JSONObject().put("input_tokens", usage.inputTokens).put("output_tokens", usage.outputTokens))
            }
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        if (outputFile != null && !outputIsMedia) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(if (outputExt == "json") body.toString(2) else response.text)
            logWrite(outputPath!!, outputFile)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    } catch (e: java.io.IOException) {
        return failure("Failed to save generated image result: ${e.message}")
    }
}

/** Images-only route keeps a strict no-image failure contract. */
internal fun saveModelUseImageResult(
    modelId: String,
    response: LLMResponse,
    outputPath: String?,
    outputExt: String,
    endpointUsed: String,
    resolveFile: (String) -> File?,
    mimeToExt: (String) -> String,
    logWrite: (String, File) -> Unit = { _, _ -> },
): NativeOffloadResult = saveModelUseResult(
    modelId, response, outputPath, outputExt, endpointUsed, true, resolveFile, mimeToExt, logWrite,
)
