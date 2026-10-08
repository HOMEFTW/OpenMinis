package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Calibration and request sizing. Text and image counters are supplied by the caller:
 * ChatViewModel uses its BPE tokenizer, including reasoning, audio and structured parts.
 * Calibration may add headroom but never shrinks that local estimate.
 */
object ContextSizeMeter {
    private const val CONTEXT_REQUEST_OVERHEAD_TOKENS = 8
    private const val CONTEXT_TOOL_OVERHEAD_TOKENS = 8
    private const val CONTEXT_PART_OVERHEAD_TOKENS = 2

    data class PayloadEstimate(val inputTokens: Int, val imageTokens: Int, val imageBytes: Long)

    internal fun estimateRequestPayload(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        textTokens: (String) -> Int,
        imageTokenCount: (ByteArray) -> Int,
    ): PayloadEstimate {
        var total = CONTEXT_REQUEST_OVERHEAD_TOKENS.toLong()
        var imageTokens = 0L
        var imageBytes = 0L

        fun add(value: Int) {
            total += value.toLong().coerceAtLeast(0L)
        }

        systemPrompt?.takeIf { it.isNotEmpty() }?.let {
            add(textTokens(it) + CONTEXT_PART_OVERHEAD_TOKENS)
        }

        // Use the largest of the provider representations so a relay with
        // a more verbose schema does not silently escape the estimate.
        for (tool in tools) {
            val schemaTokens = maxOf(
                textTokens(tool.toAnthropicJson().toString()),
                textTokens(tool.toGeminiJson().toString()),
                textTokens(tool.toOpenAIJson().toString()),
            )
            add(schemaTokens + CONTEXT_TOOL_OVERHEAD_TOKENS)
        }

        for (message in messages) {
            add(3)
            if (message.contentParts.isNotEmpty()) {
                for (part in message.contentParts) {
                    add(CONTEXT_PART_OVERHEAD_TOKENS)
                    when (part) {
                        is AgentContentPart.Text -> add(textTokens(part.text))
                        is AgentContentPart.ToolUse -> {
                            add(textTokens(part.id))
                            add(textTokens(part.name))
                            add(textTokens(part.input.toString()))
                        }
                        is AgentContentPart.ToolResult -> {
                            add(textTokens(part.id))
                            add(textTokens(part.name))
                            add(textTokens(part.content))
                            part.imageData?.let { data ->
                                imageTokens += imageTokenCount(data).toLong()
                                imageBytes += data.size.toLong()
                                add(imageTokenCount(data))
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            imageTokens += imageTokenCount(part.data).toLong()
                            imageBytes += part.data.size.toLong()
                            add(imageTokenCount(part.data))
                        }
                    }
                }
            } else {
                // Legacy messages are serialized from these fields only.
                add(textTokens(message.content))
                for (image in message.imageParts) {
                    imageTokens += imageTokenCount(image.data).toLong()
                    imageBytes += image.data.size.toLong()
                    add(imageTokenCount(image.data))
                }
                for (audio in message.audioParts) {
                    add(textTokens(audio.base64Data))
                    add(textTokens(audio.format))
                }
            }

            // Reasoning content is echoed by reasoning-capable providers
            // on historical assistant turns. Count it even when the
            // current provider may omit it; overestimating is safer than
            // sending a payload that only fails after provider expansion.
            message.reasoningContent?.let { add(textTokens(it)) }
        }

        return PayloadEstimate(
            inputTokens = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            imageTokens = imageTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            imageBytes = imageBytes,
        )
    }


    const val CALIBRATION_MIN = 1.0
    const val CALIBRATION_MAX = 3.0
    const val UNCALIBRATED_MODEL_MARGIN = 1.2
    const val CALIBRATION_FALL_RATE = 0.3

    fun calibrationRatio(reported: Int, estimated: Int): Double? =
        if (reported <= 0 || estimated <= 0) null
        else (reported.toDouble() / estimated).coerceIn(CALIBRATION_MIN, CALIBRATION_MAX)

    fun calibrated(estimated: Int, ratio: Double): Int =
        ceil(estimated.coerceAtLeast(0).toDouble() * safeRatio(ratio))
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

    private fun safeRatio(ratio: Double): Double =
        if (ratio.isFinite()) ratio.coerceIn(CALIBRATION_MIN, CALIBRATION_MAX) else 1.0

    fun smoothed(previous: Double?, sample: Double): Double {
        val next = safeRatio(sample)
        return if (previous == null || next >= previous) next
        else safeRatio(previous + CALIBRATION_FALL_RATE * (next - previous))
    }

    fun ratioSource(modelId: String?, known: Map<String, Double>, lastLearned: Double?): String = when {
        modelId != null && modelId in known -> "own"
        lastLearned == null -> "default"
        else -> "borrowed"
    }

    fun ratioFor(modelId: String?, known: Map<String, Double>, lastLearned: Double?): Double {
        modelId?.let { known[it] }?.let { return safeRatio(it) }
        return lastLearned?.let { safeRatio(maxOf(it, 1.0) * UNCALIBRATED_MODEL_MARGIN) } ?: 1.0
    }

    fun requestedTokens(detail: String): Int? {
        val cleaned = detail.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        // Require nearby token wording: timestamps and request ids are not evidence.
        val numbers = Regex("(\\d{4,})\\s*(?:tokens?\\b|个?令牌)").findAll(cleaned)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return numbers.maxOrNull()
    }

    fun ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int): Double {
        if (estimated <= 0) return safeRatio(current)
        val plausible = requested?.takeIf { it > 0 && (window <= 0 || it >= window * 0.9 && it <= window * 4.0) }
        if (plausible == null && window <= 0) return safeRatio(current)
        val target = plausible?.toDouble() ?: window * 1.02
        return safeRatio(maxOf(current, target / estimated))
    }

    data class CalibrationSample(val reported: Int, val estimated: Int, val fixedTokens: Int, val modelId: String?) {
        fun jsonFields(): String = buildString {
            append(",\"estimatedRequestTokens\":").append(estimated)
            append(",\"estimatedFixedTokens\":").append(fixedTokens)
            modelId?.let { append(",\"calibrationModelId\":").append(JSONObject.quote(it)) }
        }
    }

    data class CalibrationState(
        val ratios: Map<String, Double> = emptyMap(),
        val lastLearned: Double? = null,
        val fixedTokens: Int = 0,
        val samples: Int = 0,
    ) {
        fun carryingOver(learned: CalibrationState): CalibrationState = copy(
            ratios = ratios + learned.ratios,
            lastLearned = learned.lastLearned ?: lastLearned,
            fixedTokens = if (learned.fixedTokens > 0) learned.fixedTokens else fixedTokens,
        )
    }

    fun calibrationSample(usageJson: String): CalibrationSample? {
        val o = runCatching { JSONObject(usageJson) }.getOrNull() ?: return null
        val reported = o.optInt("latestContextTokens", 0)
        val estimated = o.optInt("estimatedRequestTokens", 0)
        if (reported <= 0 || estimated <= 0) return null
        return CalibrationSample(reported, estimated, o.optInt("estimatedFixedTokens", 0).coerceAtLeast(0),
            o.optString("calibrationModelId", "").takeIf { it.isNotBlank() && it != "null" })
    }

    fun replayCalibration(samples: List<CalibrationSample>): CalibrationState {
        val ratios = mutableMapOf<String, Double>()
        var last: Double? = null
        var fixed = 0
        var count = 0
        for (s in samples) {
            val sample = calibrationRatio(s.reported, s.estimated) ?: continue
            last = smoothed(s.modelId?.let { ratios[it] }, sample)
            s.modelId?.let { ratios[it] = last }
            fixed = s.fixedTokens
            count++
        }
        return CalibrationState(ratios.toMap(), last, fixed, count)
    }

    /** Session-local evidence survives a same-session reload, including a rejection without usage. */
    class Calibration {
        private var sessionId: String? = null
        private var state = CalibrationState()
        private val spentProbes = mutableSetOf<String>()

        @Synchronized fun seed(id: String, samples: List<CalibrationSample>): CalibrationState {
            val replayed = replayCalibration(samples)
            state = if (sessionId == id && id.isNotEmpty()) replayed.carryingOver(state) else {
                spentProbes.clear()
                replayed
            }
            sessionId = id
            return state
        }

        @Synchronized fun ratio(modelId: String): Double = ratioFor(modelId, state.ratios, state.lastLearned)
        @Synchronized fun estimate(modelId: String, raw: Int): Int = calibrated(raw, ratio(modelId))
        @Synchronized fun canProbe(modelId: String): Boolean = modelId !in spentProbes
        @Synchronized fun spendProbe(modelId: String) { spentProbes.add(modelId) }
        @Synchronized fun accepted(modelId: String) { spentProbes.remove(modelId) }

        @Synchronized fun record(sample: CalibrationSample) {
            val ratio = calibrationRatio(sample.reported, sample.estimated) ?: return
            val updated = smoothed(sample.modelId?.let { state.ratios[it] }, ratio)
            state = state.copy(
                ratios = sample.modelId?.let { state.ratios + (it to updated) } ?: state.ratios,
                lastLearned = updated, fixedTokens = sample.fixedTokens, samples = state.samples + 1,
            )
        }

        @Synchronized fun rejected(modelId: String, raw: Int, requested: Int?, window: Int): Double {
            val raised = ratioAfterOverflow(ratio(modelId), raw, requested, window)
            state = state.copy(ratios = state.ratios + (modelId to raised), lastLearned = raised)
            spentProbes.add(modelId)
            return raised
        }
    }

    /** Budget for the history alone, before applying the learned ratio and fixed request share. */
    fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
        val w = window?.takeIf { it > 0 } ?: return null
        val line = compactThreshold?.takeIf { it > 0 }?.coerceAtMost(w) ?: w
        if (fixedTokens <= 0) return null
        return (floor((line - 1).toDouble() / safeRatio(ratio)) - fixedTokens + 1)
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    /** Leave room for the active prompt and summary framing under a small user cap. */
    fun summaryOutputCeiling(window: Int, compactThreshold: Int, fixedTokens: Int, ratio: Double): Int {
        val available = warmUpBudget(window, compactThreshold, ratio, fixedTokens) ?: return 8_192
        return (available / 2).coerceIn(256, 8_192)
    }

    /** Drop whole user turns; Long sums avoid turning an oversized history into a negative size. */
    fun warmUpDrop(sizes: List<Int>, startsTurn: List<Boolean>, restTokens: Int, budget: Int): Int {
        require(sizes.size == startsTurn.size)
        var total = sizes.sumOf { it.coerceAtLeast(0).toLong() }
        var drop = 0
        while (drop < sizes.size && total + restTokens.coerceAtLeast(0).toLong() >= budget.toLong()) {
            total -= sizes[drop++].coerceAtLeast(0)
            while (drop < sizes.size && !startsTurn[drop]) total -= sizes[drop++].coerceAtLeast(0)
        }
        return drop
    }

    /** A marker's kept prefix only shrinks; a changed model/cap uses its own decision. */
    class WarmUpRetention {
        private val drops = mutableMapOf<String, Int>()
        @Synchronized fun drop(key: String, sizes: List<Int>, startsTurn: List<Boolean>, restTokens: Int, budget: Int?): Int {
            if (budget == null) return 0 // No budget yet: do not cache a false "fits".
            val drop = maxOf(drops[key] ?: 0, warmUpDrop(sizes, startsTurn, restTokens, budget))
                .coerceAtMost(sizes.size)
            drops[key] = drop
            return drop
        }
    }
}
