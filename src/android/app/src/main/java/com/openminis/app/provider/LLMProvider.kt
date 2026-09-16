package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal data class BudgetedProviderRequest(
    val messages: List<LLMMessage>,
    val imageParts: List<LLMMessage.ImagePart>,
)

/**
 * Apply the byte budget at the one public provider boundary shared by all
 * callers. Top-level imageParts and structured message images are planned as
 * one ordered occurrence list; this also makes repeated ByteArray references
 * independent of one another.
 */
internal fun budgetProviderRequest(
    messages: List<LLMMessage>,
    imageParts: List<LLMMessage.ImagePart>,
    maxRequestBytes: Long = ImageBudget.MAX_REQUEST_BYTES,
): BudgetedProviderRequest {
    data class Slot(
        val messageIndex: Int? = null,
        val partIndex: Int? = null,
        val messageImageIndex: Int? = null,
        val topLevelIndex: Int? = null,
        val data: ByteArray,
        val mimeType: String,
        val linuxPath: String?,
        var safeData: ByteArray? = null,
        var safeMimeType: String = mimeType,
        var dropped: Boolean = false,
        var requestDropped: Boolean = false,
    )

    // Structured content is the canonical representation. Drop the legacy
    // message-level field up front so a later provider fallback cannot revive
    // an image that was intentionally ignored by the budget planner.
    val canonicalMessages = messages.map { message ->
        if (message.contentParts.isNotEmpty() && message.imageParts.isNotEmpty()) {
            message.copy(imageParts = emptyList())
        } else {
            message
        }
    }

    val slots = mutableListOf<Slot>()
    canonicalMessages.forEachIndexed { messageIndex, message ->
        message.contentParts.forEachIndexed { partIndex, part ->
            when (part) {
                is AgentContentPart.ImageData -> slots += Slot(
                    messageIndex = messageIndex,
                    partIndex = partIndex,
                    data = part.data,
                    mimeType = part.mimeType,
                    linuxPath = part.linuxPath,
                )
                is AgentContentPart.ToolResult -> part.imageData?.let { data ->
                    slots += Slot(
                        messageIndex = messageIndex,
                        partIndex = partIndex,
                        data = data,
                        mimeType = part.imageMimeType ?: "image/jpeg",
                        linuxPath = part.imageLinuxPath,
                    )
                }
                else -> Unit
            }
        }
        // Legacy LLMMessage.imageParts are still consumed by the Codex image
        // generation builder only when structured content is absent.
        if (message.contentParts.isEmpty()) {
            message.imageParts.forEachIndexed { imageIndex, part ->
                slots += Slot(
                    messageIndex = messageIndex,
                    messageImageIndex = imageIndex,
                    data = part.data,
                    mimeType = part.mimeType,
                    linuxPath = part.linuxPath,
                )
            }
        }
    }
    imageParts.forEachIndexed { index, part ->
        slots += Slot(
            topLevelIndex = index,
            data = part.data,
            mimeType = part.mimeType,
            linuxPath = part.linuxPath,
        )
    }
    if (slots.isEmpty()) return BudgetedProviderRequest(canonicalMessages, imageParts)

    // Normalize each occurrence before cumulative planning. A failed ladder
    // is a hard failure for inline transport, never permission to send raw
    // bytes that still exceed the 5MB limit.
    for (slot in slots) {
        if (slot.data.size.toLong() <= ImageBudget.MAX_PER_IMAGE_BYTES) {
            slot.safeData = slot.data
        } else {
            val compressed = ImageBudget.compressUnderBudgetOrNull(slot.data)
            if (compressed == null) {
                slot.dropped = true
            } else {
                slot.safeData = compressed
                if (compressed !== slot.data) slot.safeMimeType = "image/jpeg"
            }
        }
    }

    val validSlots = slots.filter { it.safeData != null && !it.dropped }
    val requestPlan = ImageBudget.planRequestBudget(
        validSlots.map { slot ->
            ImageBudget.BudgetImage(
                data = slot.safeData!!,
                linuxPath = slot.linuxPath,
                mimeType = slot.safeMimeType,
            )
        },
        maxBytes = maxRequestBytes,
    )
    requestPlan.droppedIds.forEach { id ->
        validSlots[id.occurrence].apply {
            dropped = true
            requestDropped = true
        }
    }
    if (slots.none { it.dropped || it.safeData !== it.data }) {
        return BudgetedProviderRequest(canonicalMessages, imageParts)
    }

    val byMessagePart = slots
        .filter { it.messageIndex != null && it.partIndex != null }
        .associateBy { it.messageIndex!! to it.partIndex!! }
    val byMessageImage = slots
        .filter { it.messageIndex != null && it.messageImageIndex != null }
        .associateBy { it.messageIndex!! to it.messageImageIndex!! }

    fun placeholder(slot: Slot): String = ImageBudget.elidedImagePlaceholder(
        linuxPath = slot.linuxPath,
        maxBytes = if (slot.requestDropped) {
            ImageBudget.MAX_REQUEST_BYTES
        } else {
            ImageBudget.MAX_PER_IMAGE_BYTES
        },
    )

    val normalizedMessages = canonicalMessages.mapIndexed { messageIndex, message ->
        if (message.contentParts.isEmpty()) {
            val normalizedImageParts = message.imageParts.mapIndexedNotNull { imageIndex, original ->
                val slot = byMessageImage[messageIndex to imageIndex]
                when {
                    slot == null -> original
                    slot.dropped -> null
                    slot.safeData === original.data && slot.safeMimeType == original.mimeType -> original
                    else -> original.copy(data = slot.safeData!!, mimeType = slot.safeMimeType)
                }
            }
            val legacyPlaceholders = message.imageParts.mapIndexedNotNull { imageIndex, _ ->
                val slot = byMessageImage[messageIndex to imageIndex]
                slot?.takeIf { it.dropped }?.let(::placeholder)
            }
            if (legacyPlaceholders.isEmpty()) {
                message.copy(imageParts = normalizedImageParts)
            } else {
                message.copy(
                    content = message.content +
                        (if (message.content.isEmpty()) "" else "\n") +
                        legacyPlaceholders.joinToString("\n"),
                    imageParts = normalizedImageParts,
                )
            }
        } else {
            val normalizedParts = message.contentParts.mapIndexed { partIndex, part ->
                val slot = byMessagePart[messageIndex to partIndex]
                when {
                    slot == null -> part
                    slot.dropped && part is AgentContentPart.ImageData ->
                        AgentContentPart.Text(placeholder(slot))
                    slot.dropped && part is AgentContentPart.ToolResult -> part.copy(
                        imageData = null,
                        imageMimeType = null,
                        content = part.content +
                            (if (part.content.isEmpty()) "" else "\n") +
                            placeholder(slot),
                    )
                    part is AgentContentPart.ImageData -> part.copy(
                        data = slot.safeData!!,
                        mimeType = slot.safeMimeType,
                    )
                    part is AgentContentPart.ToolResult -> part.copy(
                        imageData = slot.safeData,
                        imageMimeType = slot.safeMimeType,
                    )
                    else -> part
                }
            }
            message.copy(contentParts = normalizedParts)
        }
    }.toMutableList()

    val topLevelSlots = slots.filter { it.topLevelIndex != null }
    val normalizedTopLevel = topLevelSlots
        .filterNot { it.dropped }
        .map { slot ->
            val original = imageParts[slot.topLevelIndex!!]
            if (slot.safeData === original.data && slot.safeMimeType == original.mimeType) {
                original
            } else {
                original.copy(data = slot.safeData!!, mimeType = slot.safeMimeType)
            }
        }
    val topLevelPlaceholders = topLevelSlots
        .filter { it.dropped }
        .map { AgentContentPart.Text(placeholder(it)) }
    if (topLevelPlaceholders.isNotEmpty()) {
        val lastUserIndex = normalizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        if (lastUserIndex >= 0) {
            val message = normalizedMessages[lastUserIndex]
            normalizedMessages[lastUserIndex] = if (message.contentParts.isNotEmpty()) {
                message.copy(contentParts = message.contentParts + topLevelPlaceholders)
            } else {
                message.copy(content = message.content +
                    (if (message.content.isEmpty()) "" else "\n") +
                    topLevelPlaceholders.joinToString("\n") { it.text })
            }
        }
    }
    return BudgetedProviderRequest(normalizedMessages, normalizedTopLevel)
}

interface LLMProvider {
    val name: String
    var model: LLMModel

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int =
        model.maxOutputTokens ?: defaultMaxOutputTokens

    /** Provider-level fallback when model.maxOutputTokens is unknown. */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse {
        val budgeted = budgetProviderRequest(messages, imageParts)
        return sendMessageClamped(
        budgeted.messages, systemPrompt, maxTokens, temperature, budgeted.imageParts, tools,
        clampThinkingLevel(thinkingLevel),
        )
    }

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> {
        val budgeted = budgetProviderRequest(messages, imageParts)
        return streamMessageClamped(
        budgeted.messages, systemPrompt, maxTokens, temperature, budgeted.imageParts, tools,
        clampThinkingLevel(thinkingLevel),
        )
    }

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Detect a silently-truncated stream.
 *
 * When a relay/upstream drops the SSE connection without an error status,
 * the provider flow completes "normally" having emitted no content and no
 * finish reason — the chat just stops mid-air with no error (user report).
 * iOS treats this as a transient error at its stream-collection layer
 * (AIChatViewModel.isEmptyResponse → LLMError.transientError) and
 * auto-retries; this operator is the Android provider-layer equivalent.
 *
 * Empty = no text / thinking / reasoning / tool-call / media chunk AND no
 * finish reason. A stream that finished WITH a stop reason but no content
 * ("stop"/"end_turn") is deliberately let through — the agent loop's
 * empty-after-tool-result reminder path (ChatViewModel) owns that case, and
 * a "length"/"max_tokens" cut is a legitimate empty. Cancellation and
 * thrown errors propagate before the check runs (code after `collect`
 * only executes on normal completion).
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var sawContent = false
    var sawFinishReason = false
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ReasoningContent -> if (chunk.content.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> sawContent = true
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) sawFinishReason = true
            else -> {}
        }
        emit(chunk)
    }
    if (!sawContent && !sawFinishReason) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}
