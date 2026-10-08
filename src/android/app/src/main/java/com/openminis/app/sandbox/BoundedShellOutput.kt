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
        val omitted = total - head.length - tail.length
        return if (omitted <= 0) head.toString() + tail
        else "$head\n[... $omitted characters omitted ...]\n$tail"
    }
}
