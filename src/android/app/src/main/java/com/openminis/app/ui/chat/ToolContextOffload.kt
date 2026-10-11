package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

internal fun offloadableToolArguments(part: AgentContentPart.ToolUse, minChars: Int): Map<String, String> {
    if (part.isOffloadedArgument || !part.thoughtSignature.isNullOrBlank()) return emptyMap()
    val fields = when (part.name) {
        "file_write" -> listOf("content")
        "file_edit" -> listOf("old_string", "new_string")
        else -> return emptyMap()
    }
    return fields.mapNotNull { field ->
        val value = part.input.opt(field) as? String ?: return@mapNotNull null
        if (value.length > minChars && !ContextOffload.isOffloadPlaceholder(value)) field to value else null
    }.toMap()
}

/** Changing a signed assistant block invalidates the provider's replay hash. */
internal fun LLMMessage.hasSignedToolReplay(): Boolean = deepSeekReplay != null || anthropicReplay != null

/** Replace history only after every selected argument has been saved successfully. */
internal fun offloadToolArguments(
    part: AgentContentPart.ToolUse,
    minChars: Int,
    tokens: Int,
    write: (field: String, content: String) -> String,
): AgentContentPart.ToolUse? {
    val fields = offloadableToolArguments(part, minChars)
    if (fields.isEmpty()) return null
    val input = JSONObject(part.input.toString())
    for ((field, content) in fields) {
        val path = write(field, content)
        if (path.isEmpty()) return null
        input.put(field, ContextOffload.prunedArgumentNotice(tokens, content.toByteArray(Charsets.UTF_8).size, path))
    }
    return part.copy(input = input, isOffloadedArgument = true)
}

/** Keep both modalities and all saved paths, including when only the image is large. */
internal fun offloadToolResult(
    part: AgentContentPart.ToolResult,
    tokens: Int,
    bytes: Int,
    minTextChars: Int = 500,
    minImageBytes: Int = 1024,
    writeText: (String) -> String,
    writeImage: (ByteArray) -> String,
): AgentContentPart.ToolResult? {
    val saveText = part.content.length > minTextChars
    val saveImage = (part.imageData?.size ?: 0) > minImageBytes
    if (!saveText && !saveImage) return null
    val textPath = if (saveText) writeText(part.content).takeIf { it.isNotEmpty() } ?: return null else null
    val imagePath = if (saveImage) writeImage(part.imageData!!).takeIf { it.isNotEmpty() } ?: return null else null
    val notices = listOfNotNull(textPath, imagePath).map { ContextOffload.stub(tokens, bytes, it) }
    val retainedText = part.content.takeIf { !saveText && it.isNotEmpty() }
    return part.copy(
        content = (notices + listOfNotNull(retainedText)).joinToString("\n\n"),
        imageData = if (saveImage) null else part.imageData,
        imageMimeType = if (saveImage) null else part.imageMimeType,
        imageLinuxPath = imagePath ?: part.imageLinuxPath,
    )
}
