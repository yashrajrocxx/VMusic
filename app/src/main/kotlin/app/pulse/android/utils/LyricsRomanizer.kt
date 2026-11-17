package app.pulse.android.utils

import android.icu.text.Transliterator
import app.pulse.providers.lrclib.LrcParser

/**
 * Latin-script ("romanized") variants of lyrics.
 *
 * Providers that ship a romanization (Better Lyrics' TTML `x-roman` spans) are
 * preferred by the callers; this utility covers the providers that only return
 * the original script and keeps synchronized timestamps intact while doing so.
 */
object LyricsRomanizer {

    private val transliteratorLock = Any()
    private var transliterator: Transliterator? = null

    private val inlineTagRegex = Regex("\\{(?:agent|bg)[^}]*\\}")

    /** True when [text] contains at least one letter outside the Latin script. */
    fun needsRomanization(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.LATIN,
                    Character.UnicodeScript.COMMON,
                    Character.UnicodeScript.INHERITED -> Unit

                    else -> return true
                }
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Returns the Latin-script form of [text], or null when the text is already
     * Latin or cannot be transliterated.
     */
    fun romanize(text: String): String? {
        if (text.isBlank() || !needsRomanization(text)) return null
        val result = synchronized(transliteratorLock) {
            val instance = transliterator ?: runCatching {
                Transliterator.getInstance("Any-Latin; Latin-ASCII")
            }.getOrNull()?.also { transliterator = it }
                ?: return null
            runCatching { instance.transliterate(text) }.getOrNull()
        }
        return result?.takeIf { it.isNotBlank() && it != text }
    }

    /**
     * Romanizes a synchronized LRC while preserving timestamps, agent tags and
     * word-level timing tokens. Returns null when nothing changed.
     */
    fun romanizeLrc(lrc: String): String? {
        if (!needsRomanization(lrc)) return null

        var changed = false
        val output = StringBuilder(lrc.length + 64)
        lrc.lineSequence().forEachIndexed { index, line ->
            if (index > 0) output.append('\n')
            val romanized = romanizeLrcLine(line)
            if (romanized != line) changed = true
            output.append(romanized)
        }
        return output.toString().takeIf { changed }
    }

    /** Extracts a plain-text version of an LRC file (used when a provider only returns synced lyrics). */
    fun lrcToPlainText(lrc: String): String? {
        val parsed = runCatching { LrcParser.parse(lrc) }.getOrNull()
            ?: return lrc.trim()
                .takeIf { it.isNotBlank() && !it.contains('[') }

        val lines = parsed
            .filterIsInstance<LrcParser.Line.Lyric>()
            .sortedBy { it.timestamp }
            .map { it.line.replace(inlineTagRegex, "").trim() }
            .filter { it.isNotBlank() }

        return lines
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /** True when [text] contains at least one timed, non-empty LRC line. */
    fun isSynced(text: String): Boolean {
        val parsed = runCatching { LrcParser.parse(text) }.getOrNull() ?: return false
        return parsed.any { it is LrcParser.Line.Lyric && it.line.isNotBlank() }
    }

    private fun romanizeLrcLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return line
        if (trimmed.startsWith("<")) return romanizeWordTimingLine(trimmed)

        val timestampEnd = line.lastIndexOf(']')
        if (timestampEnd == -1) return romanizeInlineTags(line)

        val prefix = line.substring(0, timestampEnd + 1)
        val text = line.substring(timestampEnd + 1)
        if (text.isEmpty() || !needsRomanization(text)) return line
        return prefix + romanizeInlineTags(text)
    }

    private fun romanizeInlineTags(text: String): String {
        if (!needsRomanization(text)) return text

        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '{') {
                val end = text.indexOf('}', index)
                if (end != -1) {
                    output.append(text, index, end + 1)
                    index = end + 1
                    continue
                }
            }

            val nextTag = text.indexOf('{', index).let { if (it == -1) text.length else it }
            val segment = text.substring(index, nextTag)
            output.append(romanize(segment) ?: segment)
            index = nextTag
        }
        return output.toString()
    }

    /**
     * Word-level tokens look like `<word:0.0:1.25|word:1.25:2.0>`. Only the
     * leading word text is romanized; the two timing numbers stay untouched.
     */
    private fun romanizeWordTimingLine(line: String): String {
        if (!line.endsWith(">")) return line
        val body = line.substring(1, line.length - 1)
        val tokens = body.split('|').map { token ->
            val end = token.lastIndexOf(':')
            val start = if (end > 0) token.lastIndexOf(':', end - 1) else -1
            if (start <= 0 || end <= start) return@map token
            val word = token.substring(0, start)
            val timing = token.substring(start)
            (romanize(word) ?: word) + timing
        }
        return "<" + tokens.joinToString("|") + ">"
    }
}
