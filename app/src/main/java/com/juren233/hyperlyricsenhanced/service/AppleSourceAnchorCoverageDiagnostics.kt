package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine

/** Why a complete-sounding Hangul line still needs a line-wide fallback:
 * missing/partial dictionary anchors versus a full source map that could not
 * follow Apple's native word cells. Export only aggregate counts, never text. */
internal object AppleSourceAnchorCoverageDiagnostics {
    data class Summary(
        val full: Int,
        val partial: Int,
        val absent: Int,
        val alignedWords: Int,
    )

    fun summarize(lines: List<RichLyricLine>): Summary {
        var full = 0
        var partial = 0
        var absent = 0
        var alignedWords = 0
        for (line in lines) {
            val spans = line.pronunciationSpans.orEmpty()
            if (line.pronunciationWords != null) alignedWords++
            if (spans.isEmpty()) {
                absent++
                continue
            }
            if (isComplete(line)) full++ else partial++
        }
        return Summary(full, partial, absent, alignedWords)
    }

    private fun isComplete(line: RichLyricLine): Boolean {
        val source = line.text.orEmpty()
        val pronunciation = line.roma.orEmpty()
        if (source.codePoints().noneMatch(Character::isLetterOrDigit) ||
            pronunciation.codePoints().noneMatch(Character::isLetterOrDigit)
        )
            return false
        val covered = BooleanArray(source.length)
        val sound = StringBuilder()
        var previousEnd = 0
        for (span in line.pronunciationSpans.orEmpty()) {
            if (span.start < previousEnd || span.start < 0 ||
                span.end <= span.start || span.end > source.length ||
                (span.start > 0 && span.start < source.length &&
                    Character.isHighSurrogate(source[span.start - 1]) &&
                    Character.isLowSurrogate(source[span.start])) ||
                (span.end > 0 && span.end < source.length &&
                    Character.isHighSurrogate(source[span.end - 1]) &&
                    Character.isLowSurrogate(source[span.end])) ||
                source.substring(span.start, span.end) != span.source ||
                span.hangul.none(Char::isLetterOrDigit)
            ) return false
            for (index in span.start until span.end) covered[index] = true
            sound.append(span.hangul.filter(Char::isLetterOrDigit))
            previousEnd = span.end
        }
        var offset = 0
        while (offset < source.length) {
            val codePoint = Character.codePointAt(source, offset)
            val end = offset + Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint) &&
                (offset until end).any { !covered[it] }
            ) return false
            offset = end
        }
        return sound.toString() == pronunciation.filter(Char::isLetterOrDigit)
    }
}
