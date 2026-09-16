package com.openminis.app.ui.chat

import kotlin.math.ceil

/** Calibrate the current payload, rather than retaining the previous payload's size. */
internal class ContextTokenCalibration {
    private var key: String? = null
    private var scale = 1.0

    @Synchronized
    fun record(requestKey: String, estimatedInput: Int, reportedInput: Int) {
        if (estimatedInput <= 0 || reportedInput <= 0) return
        key = requestKey
        scale = maxOf(1.0, reportedInput.toDouble() / estimatedInput)
    }

    @Synchronized
    fun estimate(requestKey: String, currentEstimate: Int): Int =
        ceil(currentEstimate.coerceAtLeast(0).toDouble() * if (key == requestKey) scale else 1.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}
