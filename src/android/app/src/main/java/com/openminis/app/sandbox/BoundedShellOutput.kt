package com.openminis.app.sandbox

/** Keep useful beginning/end output while continuing to drain arbitrarily large pipes. */
internal class BoundedShellOutput(private val keepEach: Int = 20_000) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var total = 0L

    fun append(text: String): BoundedShellOutput {
        total += text.length
        val headCount = minOf((keepEach - head.length).coerceAtLeast(0), text.length)
        head.append(text, 0, headCount)
        tail.append(text, headCount, text.length)
        if (tail.length > keepEach) tail.delete(0, tail.length - keepEach)
        return this
    }
    fun append(char: Char): BoundedShellOutput = append(char.toString())
    fun isEmpty() = total == 0L
    fun isNotEmpty() = total != 0L
    fun last(): Char = if (tail.isNotEmpty()) tail.last() else head.last()
    override fun toString(): String {
        if (total <= head.length + tail.length) return head.toString() + tail
        // A retention boundary can split a surrogate pair even after the byte
        // stream was decoded correctly. Trim the dangling halves only when
        // text was omitted; an untruncated head/tail pair still joins intact.
        val headEnd = head.length - if (head.lastOrNull()?.isHighSurrogate() == true) 1 else 0
        val tailStart = if (tail.firstOrNull()?.isLowSurrogate() == true) 1 else 0
        val omitted = total - headEnd - (tail.length - tailStart)
        return "${head.substring(0, headEnd)}\n[... $omitted characters omitted ...]\n${tail.substring(tailStart)}"
    }
}
