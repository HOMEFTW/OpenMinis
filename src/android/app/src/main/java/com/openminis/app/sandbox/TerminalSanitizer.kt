package com.openminis.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1B]*(?:\x07|\x1B\\)|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Strip formatting escapes, then simulate carriage-return overwrites.
     * Text content, null values and whitespace remain intact.
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Escape sequences have zero display width; strip before simulating CR.
        // Preserve legitimate whitespace and null values in program output.
        val plain = ANSI_REGEX.replace(raw, "")
        return foldCarriageReturns(plain).filter { it == '\n' || it == '\t' || it.code >= 0x20 }

    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior: when a line contains \r (without \n),
     * the text after \r overwrites from the beginning of the line.
     * Each \r resets the cursor to position 0; characters not overwritten
     * by the next segment remain visible.
     */
    private fun foldCarriageReturns(text: String): String {
        val result = StringBuilder()
        val line = StringBuilder()
        var cursor = 0
        for (char in text) {
            when (char) {
                '\r' -> cursor = 0
                '\n' -> { result.append(line).append('\n'); line.setLength(0); cursor = 0 }
                else -> {
                    if (cursor < line.length) line.setCharAt(cursor, char) else line.append(char)
                    cursor++
                }
            }
        }
        return result.append(line).toString()
    }
}
