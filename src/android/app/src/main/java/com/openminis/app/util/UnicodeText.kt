package com.openminis.app.util

/** Keep a UTF-16 character budget without leaving half of a supplementary character. */
internal fun String.takeUnicodeSafe(maxChars: Int): String {
    require(maxChars >= 0)
    var end = minOf(length, maxChars)
    if (end > 0 && end < length && Character.isHighSurrogate(this[end - 1]) && Character.isLowSurrogate(this[end])) end--
    return substring(0, end)
}
