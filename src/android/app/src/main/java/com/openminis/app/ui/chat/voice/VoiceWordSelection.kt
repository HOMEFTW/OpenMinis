package com.openminis.app.ui.chat.voice

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.input.TextFieldValue

/** Selection changes must leave the transcript text intact and finish IME composition. */
internal fun selectTranscriptWord(
    value: TextFieldValue,
    offset: Int,
    wordBoundary: (Int) -> androidx.compose.ui.text.TextRange,
): TextFieldValue {
    if (value.text.isEmpty()) return value
    return value.copy(
        selection = wordBoundary(offset.coerceIn(0, value.text.lastIndex)),
        composition = null,
    )
}

internal class VoiceDoubleTapTracker(
    private val minIntervalMillis: Long,
    private val maxIntervalMillis: Long,
    private val maxDistance: Float,
) {
    private var previousTime: Long? = null
    private var previousPosition = Offset.Zero

    fun reset() { previousTime = null }

    fun onTap(timeMillis: Long, position: Offset): Boolean {
        val previous = previousTime
        val isDoubleTap = previous != null &&
            timeMillis - previous in minIntervalMillis..maxIntervalMillis &&
            (position - previousPosition).getDistance() <= maxDistance
        previousTime = if (isDoubleTap) null else timeMillis
        previousPosition = position
        return isDoubleTap
    }
}

/** Observe releases after the text field handles them; never consume cursor/drag/IME gestures. */
internal suspend fun PointerInputScope.observeVoiceDoubleTaps(
    doubleTapSlop: Float,
    onDoubleTap: (Offset) -> Unit,
) {
    val tracker = VoiceDoubleTapTracker(
        viewConfiguration.doubleTapMinTimeMillis,
        viewConfiguration.doubleTapTimeoutMillis,
        doubleTapSlop,
    )
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var last = down
        var isTap = currentEvent.changes.count { it.pressed } == 1
        while (last.pressed) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.count { it.pressed } > 1 ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) isTap = false
            last = change
        }
        if (isTap && !last.pressed &&
            last.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        ) {
            if (tracker.onTap(last.uptimeMillis, last.position)) onDoubleTap(last.position)
        } else {
            tracker.reset()
        }
    }
}
