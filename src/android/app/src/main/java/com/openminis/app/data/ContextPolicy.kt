package com.openminis.app.data

/**
 * Pure-logic policy that decides, given a token estimate and the model's
 * context window, whether the agent loop should offload big tool results,
 * trigger a compact/summarize pass, or surface "context exhausted" to the UI.
 *
 * Mirrors iOS `ContextPolicy` (ContextPolicy.swift): 4-tier thresholds keyed
 * off the model's context window, with no summarization algorithm embedded —
 * the actual compact + offload execution lives in the agent loop, this struct
 * only answers "what state are we in?".
 *
 * Thresholds intentionally leave headroom (10k/20k/40k below the ceiling) so
 * one more agent turn can still fit before the user sees any disruption.
 */
data class ContextPolicy(
    /** Above this token count, next tool result should be written to disk. 0 disables. */
    val offloadThreshold: Int,
    /** After offload, shrink context toward this target (lower than threshold). */
    val offloadTarget: Int,
    /** Above this, trigger a compact/summarize pass. 0 disables. */
    val compactThreshold: Int,
    /** When true, the tier is too small for auto-compact; only surface .exhausted. */
    val exhaustedOnly: Boolean,
    /** Whether the "Compact now" button is offered in the UI. */
    val manualCompactAllowed: Boolean,
) {
    data class Window(val tokens: Int, val isUserCap: Boolean)

    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * Classify the current turn's token pressure. Priority:
     *   1. If compact is available and tokens crossed [compactThreshold] → NEEDS_COMPACT.
     *   2. If this is a small-window tier (`exhaustedOnly`) and we're past the
     *      offload line or 90% of the window → EXHAUSTED.
     *   3. Otherwise OK.
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        if (contextWindow <= 0) return CheckResult.OK
        if (estimatedTokens >= contextWindow) {
            return if (manualCompactAllowed) CheckResult.NEEDS_COMPACT else CheckResult.EXHAUSTED
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow.toLong() * 9 / 10).toInt()
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** Whether the next tool result should be offloaded to disk. */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    enum class InLoopStep { PROCEED, COMPACT, SEND_WITHIN_WINDOW, SEND_UNCALIBRATED_ONCE, STOP }

    companion object {
        /** Both the local global setting and the group limit are user caps. */
        fun resolveWindow(modelWindow: Int?, configuredWindow: Int?, groupLimit: Int?): Window? {
            val native = modelWindow?.takeIf { it > 0 }
            val cap = listOfNotNull(configuredWindow, groupLimit).filter { it > 0 }.minOrNull()
            val tokens = listOfNotNull(native, cap).minOrNull() ?: return null
            return Window(tokens, cap != null && (native == null || cap < native))
        }

        /** A compact line is advisory; a user-chosen cap is never bypassed by a probe. */
        fun inLoopStep(
            verdict: CheckResult,
            measured: Int,
            rawTokens: Int,
            window: Int,
            canCompact: Boolean,
            ratio: Double,
            uncalibratedSendUsed: Boolean,
            isUserCap: Boolean = false,
        ): InLoopStep = when {
            window <= 0 -> InLoopStep.PROCEED
            verdict == CheckResult.OK -> InLoopStep.PROCEED
            verdict == CheckResult.EXHAUSTED -> InLoopStep.STOP
            canCompact -> InLoopStep.COMPACT
            measured < window -> InLoopStep.SEND_WITHIN_WINDOW
            !isUserCap && !uncalibratedSendUsed && ratio > 1.0 && rawTokens < window ->
                InLoopStep.SEND_UNCALIBRATED_ONCE
            else -> InLoopStep.STOP
        }

        /** User caps keep compaction available even below the native-window tiers. */
        fun forUserCap(contextWindow: Int): ContextPolicy = ContextPolicy(
            offloadThreshold = (contextWindow * 0.70).toInt().coerceAtLeast(1),
            offloadTarget = (contextWindow * 0.55).toInt(),
            compactThreshold = (contextWindow * 0.85).toInt().coerceAtLeast(1),
            exhaustedOnly = false,
            manualCompactAllowed = true,
        )

        /**
         * Produce the policy for a given context window size. Four tiers:
         *   - `<32K`   → offload/compact disabled; UI tells user to start a new chat.
         *   - `32K–64K` → offload only; exhaust line = ctx − 10k.
         *   - `64K–128K` → offload + compact; headroom 10k for compact.
         *   - `≥128K`  → generous offload + compact; headroom 20k.
         */
        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 10_000,
                offloadTarget = contextWindow - 15_000,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 20_000,
                offloadTarget = contextWindow - 30_000,
                compactThreshold = contextWindow - 10_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = contextWindow - 40_000,
                offloadTarget = contextWindow - 60_000,
                compactThreshold = contextWindow - 20_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }
    }
}
