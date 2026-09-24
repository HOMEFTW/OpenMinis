package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import java.io.File

/** Select recent images before reading any pixels. Stored history remains file-backed. */
internal fun loadHistoryImages(
    messages: List<LLMMessage>,
    maxBytes: Long = ImageBudget.MAX_REQUEST_BYTES,
    onBudgetElision: (ImageBudget.RequestBudgetPlan) -> Unit = {},
    load: (File, String) -> LLMMessage.ImagePart?,
): List<LLMMessage> {
    var remaining = maxBytes.coerceAtLeast(0)
    val total = messages.sumOf { message -> message.contentParts.count {
        it is AgentContentPart.ImageData || (it is AgentContentPart.ToolResult && it.imageData != null)
    } }
    var occurrence = total
    var elidedBytes = 0L
    val dropped = linkedMapOf<ImageBudget.ImagePartId, String?>()
    fun elide(image: AgentContentPart.ImageData, size: Long): AgentContentPart.Text {
        dropped[ImageBudget.ImagePartId.of(occurrence)] = image.linuxPath
        elidedBytes += size
        return AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(image.linuxPath))
    }
    val result = messages.toMutableList()
    for (mi in messages.indices.reversed()) {
        val message = messages[mi]
        val parts = message.contentParts.toMutableList()
        for (pi in parts.indices.reversed()) {
            val part = parts[pi]
            val image = part as? AgentContentPart.ImageData
            if (image != null || (part is AgentContentPart.ToolResult && part.imageData != null)) occurrence--
            if (image?.localPath == null) {
                val size = when (part) {
                    is AgentContentPart.ImageData -> part.data.size.toLong()
                    is AgentContentPart.ToolResult -> part.imageData?.size?.toLong() ?: 0L
                    else -> 0L
                }
                remaining = (remaining - size).coerceAtLeast(0)
                continue
            }
            val file = File(image.localPath)
            val estimate = minOf(file.length(), ImageBudget.MAX_PER_IMAGE_BYTES)
            parts[pi] = if (estimate > remaining || remaining == 0L) {
                elide(image, estimate)
            } else {
                val loaded = if (file.isFile) load(file, image.mimeType) else null
                when {
                    loaded == null || loaded.data.isEmpty() -> AgentContentPart.Text(
                        "[Image unavailable or invalid${image.linuxPath?.let { ": $it" } ?: ""}]",
                    )
                    loaded.data.size > remaining -> elide(image, loaded.data.size.toLong())
                    else -> {
                        remaining -= loaded.data.size
                        image.copy(data = loaded.data, mimeType = loaded.mimeType, localPath = null)
                    }
                }
            }
        }
        result[mi] = message.copy(contentParts = parts)
    }
    if (dropped.isNotEmpty()) onBudgetElision(ImageBudget.RequestBudgetPlan(
        dropped.keys, dropped, maxBytes.coerceAtLeast(0) - remaining, elidedBytes, dropped.size, total,
    ))
    return result
}
