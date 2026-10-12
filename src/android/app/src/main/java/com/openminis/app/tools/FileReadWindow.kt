package com.openminis.app.tools

import java.io.File

/** Two streaming passes retain only the requested character budget, even for one huge line. */
internal object FileReadWindow {
    data class Window(
        val totalLines: Long,
        val startLine: Long,
        val endLine: Long,
        val content: String,
        val truncated: Boolean,
        val nextOffset: Long?,
    )

    fun read(file: File, offset: Int, lines: Int?, maxLength: Int, tail: Boolean): Window {
        val total = countLines(file)
        val count = lines?.coerceAtLeast(0)?.toLong() ?: total
        val start = if (tail) (total - count).coerceAtLeast(0) + 1 else offset.toLong()
        val end = minOf(total, start + count - 1)
        if (start > end) return Window(total, start, start - 1, "", false, null)

        val chars = CharWindow(maxLength + 1, tail)
        file.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            var line = 1L
            var previousCr = false
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buffer[i]
                    if (c == '\n' && previousCr) { previousCr = false; continue }
                    if (c == '\n' || c == '\r') {
                        if (line >= end) return@use
                        if (line >= start && !chars.append('\n')) return@use
                        line++
                        previousCr = c == '\r'
                    } else {
                        previousCr = false
                        if (line >= start && !chars.append(c)) return@use
                    }
                }
            }
        }
        var content = chars.text()
        val truncated = content.length > maxLength
        var effectiveStart = start
        var effectiveEnd = end
        var nextOffset: Long? = null
        if (truncated) {
            if (tail) {
                content = content.takeLast(maxLength)
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) content = content.substring(firstNewline + 1)
                effectiveStart = end - content.count { it == '\n' }
            } else {
                content = content.take(maxLength)
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = start + content.count { it == '\n' }
                if (effectiveEnd < total) nextOffset = effectiveEnd + 1
            }
        }
        return Window(total, effectiveStart, effectiveEnd, content, truncated, nextOffset)
    }

    private fun countLines(file: File): Long = file.bufferedReader().use { reader ->
        val buffer = CharArray(8192)
        var lines = 0L
        var previousCr = false
        var hasContent = false
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buffer[i]
                if (c == '\n' && previousCr) { previousCr = false; continue }
                if (c == '\n' || c == '\r') {
                    lines++
                    hasContent = false
                    previousCr = c == '\r'
                } else {
                    hasContent = true
                    previousCr = false
                }
            }
        }
        lines + if (hasContent) 1 else 0
    }

    private class CharWindow(private val capacity: Int, private val tail: Boolean) {
        private val buffer = CharArray(capacity)
        private var start = 0
        private var size = 0

        fun append(c: Char): Boolean {
            if (size < capacity) {
                buffer[(start + size) % capacity] = c
                size++
            } else {
                buffer[start] = c
                start = (start + 1) % capacity
            }
            return tail || size < capacity
        }

        fun text(): String = buildString(size) {
            repeat(size) { append(buffer[(start + it) % capacity]) }
        }
    }
}
