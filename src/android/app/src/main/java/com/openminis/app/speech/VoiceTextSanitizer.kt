package com.openminis.app.speech

import com.openminis.app.ui.markdown.MarkdownWorkLimits

/**
 * [T-android-voice-text-sanitizer] Cleans a text unit before it is handed to
 * TTS. Port of iOS `Providers/Voice/VoiceTextSanitizer.swift`.
 *
 * Without this, read-aloud pronounces raw Markdown — "star star important star
 * star", every table pipe, and whole URLs character by character — and stumbles
 * over emoji and box-drawing glyphs. It removes the syntax that shouldn't be
 * spoken while keeping the visible text and the punctuation that drives
 * prosody.
 *
 * Ordering matters and mirrors iOS: fenced code → inline code → links/images →
 * emphasis → line-leading block markers → leftover markers.
 *
 * DIVERGENCE FROM iOS (deliberate): iOS hardcodes the Chinese link phrases
 * "<host> 的链接" / "链接". Here the phrase is supplied by the caller via
 * [LinkPhrases] so it can come from `R.string` and follow the device locale —
 * the project requires localized user-facing strings, and TTS output is
 * user-facing. Defaults are English so the class stays usable (and unit
 * testable) without a Context.
 */
object VoiceTextSanitizer {

    /**
     * Localized wording for spoken link substitutions.
     *
     * @param linkToHost formats a known host; must contain a single `%s`.
     * @param bareLink used when no host can be extracted.
     */
    data class LinkPhrases(
        val linkToHost: String = "link to %s",
        val bareLink: String = "link",
    )

    private val DEFAULT_PHRASES = LinkPhrases()
    private val spacesRegex = Regex("[ \\t]{2,}")
    private val newlineSpacesRegex = Regex(" *\\n *")
    private val fenceRegex = Regex("```[\\s\\S]*?```")
    private val codeRegex = Regex("`([^`]+)`")
    private val boldStarRegex = Regex("\\*\\*([^*]+)\\*\\*")
    private val boldUnderscoreRegex = Regex("__([^_]+)__")
    private val italicStarRegex = Regex("\\*([^*]+)\\*")
    private val italicUnderscoreRegex = Regex("(?<!\\w)_([^_]+)_(?!\\w)")
    private val strikeRegex = Regex("~~([^~]+)~~")
    private val headingRegex = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val quoteRegex = Regex("(?m)^\\s{0,3}>\\s?")
    private val bulletRegex = Regex("(?m)^\\s{0,3}[-*+]\\s+")
    private val orderedRegex = Regex("(?m)^\\s{0,3}\\d+[.)]\\s+")
    private val tableSeparatorRegex = Regex("(?m)^\\s*\\|?[-:| ]+\\|?\\s*$")
    private val ruleRegex = Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$")
    private val leftoverUnderscoreRegex = Regex("(?<!\\w)_+|(?<!_)_+(?!\\w)")
    private val leftoverMarkersRegex = Regex("[*~`]+")
    private val markdownLinkRegex = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)")
    private val bareUrlRegex = Regex("https?://[^\\s)\\]]+")

    /**
     * Strip Markdown syntax and non-speakable glyphs, then tidy whitespace.
     * Returns the cleaned, speakable string — which may legitimately be empty
     * (e.g. a message that was nothing but a fenced code block), and callers
     * must treat empty as "nothing to speak" rather than speaking the original.
     */
    fun sanitize(text: String, phrases: LinkPhrases = DEFAULT_PHRASES): String {
        // Huge input keeps its text and glyph cleanup, but skips rich syntax matching.
        val canParse = text.length <= MarkdownWorkLimits.MAX_INLINE_CHARS && MarkdownWorkLimits.canParseBlocks(text)
        var s = if (canParse) stripMarkdown(text, phrases) else text
        // Any remaining bare URL in plain text → spoken phrase, so the reader
        // never voices a long, unstoppable URL.
        if (canParse) s = rewriteBareUrls(s, phrases)
        // Replace non-speakable glyphs with a space rather than deleting them,
        // so "word🔥word" doesn't fuse into one token.
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isSpeakable(cp)) sb.appendCodePoint(cp) else sb.append(' ')
            i += Character.charCount(cp)
        }
        // Collapse the whitespace the removals left behind.
        return sb.toString()
            .replace(spacesRegex, " ")
            .replace(newlineSpacesRegex, "\n")
            .trim()
    }

    /**
     * Remove the Markdown syntax that shouldn't be pronounced, keeping the
     * visible text.
     */
    private fun stripMarkdown(text: String, phrases: LinkPhrases): String {
        var s = text
        // Fenced code blocks are dropped entirely — reading code aloud is noise.
        s = s.replace(fenceRegex, " ")
        // Inline `code` → keep the inner text.
        s = s.replace(codeRegex, "$1")
        // Links/images before emphasis, so a description containing * is safe.
        s = rewriteMarkdownLinks(s, phrases)
        // Emphasis: **x** __x__ *x* _x_ ~~x~~ → x
        s = s.replace(boldStarRegex, "$1")
        s = s.replace(boldUnderscoreRegex, "$1")
        s = s.replace(italicStarRegex, "$1")
        // Intra-word underscores (snake_case) must survive, hence the guards.
        s = s.replace(italicUnderscoreRegex, "$1")
        s = s.replace(strikeRegex, "$1")
        // Line-leading block markers.
        s = s.replace(headingRegex, "")        // # headings
        s = s.replace(quoteRegex, "")          // > blockquote
        s = s.replace(bulletRegex, "")         // - bullet
        s = s.replace(orderedRegex, "")        // 1. ordered
        s = s.replace(tableSeparatorRegex, " ") // table separator
        s = s.replace('|', ' ')                // table pipes
        // Horizontal rules --- *** ___
        s = s.replace(ruleRegex, " ")
        // Stray leftover emphasis markers.
        //
        // DIVERGENCE FROM iOS (bug fix): iOS strips `[*_~`]{1,}` unconditionally,
        // which also eats the underscores in identifiers — "read_image_file"
        // became "readimagefile", defeating the (?<!\w)_..._(?!\w) guard above.
        // Underscores are only stripped when NOT sitting between word
        // characters, so snake_case survives; the other markers are never
        // meaningful mid-identifier and stay unconditional.
        s = s.replace(leftoverUnderscoreRegex, "")
        s = s.replace(leftoverMarkersRegex, "")
        return s
    }

    /**
     * Rewrite `[desc](url)` / `![alt](url)`: a non-empty description is kept
     * verbatim; an empty one becomes the spoken link phrase.
     */
    private fun rewriteMarkdownLinks(text: String, phrases: LinkPhrases): String {
        return markdownLinkRegex.replace(text) { m ->
            val desc = m.groupValues[1].trim()
            if (desc.isEmpty()) linkPhrase(m.groupValues[2], phrases) else desc
        }
    }

    /** Replace bare http(s) URLs in plain text with the spoken link phrase. */
    private fun rewriteBareUrls(text: String, phrases: LinkPhrases): String =
        bareUrlRegex.replace(text) { m -> linkPhrase(m.value, phrases) }

    private fun linkPhrase(url: String, phrases: LinkPhrases): String {
        val host = hostOf(url)
        return if (host.isEmpty()) phrases.bareLink else phrases.linkToHost.format(host)
    }

    /** Host without a leading "www.", or "" when nothing usable can be parsed. */
    internal fun hostOf(url: String): String {
        var s = url
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        // Strip userinfo and port so "user@host:8080" reads as "host".
        s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) }
        s.indexOf(':').let { if (it >= 0) s = s.substring(0, it) }
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    /**
     * True when the code point should be spoken. Drops emoji, dingbats, box
     * drawing and other symbol ranges that a TTS engine either skips or
     * verbalizes awkwardly. Ranges mirror the iOS implementation.
     */
    private fun isSpeakable(cp: Int): Boolean {
        // Keep all ASCII: letters, digits, punctuation, whitespace.
        if (cp < 0x80) return true
        return when (cp) {
            in 0x200B..0x200F,        // zero-width / directional marks
            in 0xFE00..0xFE0F,        // variation selectors
            in 0x2190..0x21FF,        // arrows
            in 0x2300..0x23FF,        // misc technical
            in 0x2460..0x24FF,        // enclosed alphanumerics
            in 0x2500..0x257F,        // box drawing
            in 0x2580..0x259F,        // block elements
            in 0x25A0..0x25FF,        // geometric shapes
            in 0x2600..0x26FF,        // misc symbols
            in 0x2700..0x27BF,        // dingbats
            in 0x2B00..0x2BFF,        // misc symbols & arrows
            in 0x1F000..0x1FAFF,      // emoji / pictographs
            in 0xE0000..0xE007F,      // tags
            -> false
            0x20E3 -> false           // combining keycap
            else -> true
        }
    }
}
