package com.openminis.app.ui.markdown

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

internal fun markdownThrottleMillis(chars: Int): Long = when {
    chars < 500 -> 200L
    chars < 2_000 -> 300L
    chars < 32_000 -> 500L
    chars < 64_000 -> 1_000L
    chars < 128_000 -> 1_500L
    else -> 2_000L
}

/** Keep collecting across content updates so a new token cannot reset the delay. */
internal fun Flow<String>.throttleMarkdownUpdates(): Flow<String> = conflate().transform { latest ->
    emit(latest)
    delay(markdownThrottleMillis(latest.length))
}
