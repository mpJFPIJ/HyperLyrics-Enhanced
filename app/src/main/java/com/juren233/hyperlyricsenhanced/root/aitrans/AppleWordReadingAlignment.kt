package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation

internal enum class AlignmentFailure {
    NO_WORDS, SOURCE_MISMATCH, INVALID_SPANS, BOUNDARY_SPLIT, SOUND_MISMATCH
}

internal sealed interface WordAlignmentResult {
    data class Verified(val words: List<LyricWordPronunciation>) : WordAlignmentResult
    /** A complete source token crosses native word cells; sound stays intact on its first cell. */
    data class Grouped(val words: List<LyricWordPronunciation>) : WordAlignmentResult
    data class LineWide(val reason: AlignmentFailure) : WordAlignmentResult
}

/** Never invents a karaoke syllable boundary: it must already exist in the source. */
internal object AppleWordReadingAlignment {
    fun align(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        expectedHangul: String? = null,
    ): WordAlignmentResult {
        if (appleWords.isEmpty()) return WordAlignmentResult.LineWide(AlignmentFailure.NO_WORDS)
        if (spans.isEmpty()) return WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS)

        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (word in appleWords) {
            val value = word.trim()
            if (value.isEmpty() || value.contains('<') || value.contains('>')) {
                return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
            }
            while (cursor < original.length && original[cursor].isWhitespace()) cursor++
            if (!original.startsWith(value, cursor)) {
                return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
            }
            ranges += cursor until (cursor + value.length)
            cursor += value.length
        }
        if (original.substring(cursor).any { !it.isWhitespace() }) {
            return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
        }

        val sounds = MutableList(appleWords.size) { StringBuilder() }
        var previousEnd = 0
        var grouped = false
        for (span in spans) {
            if (span.sourceStart < previousEnd || span.sourceEnd <= span.sourceStart ||
                span.sourceEnd > original.length ||
                original.substring(span.sourceStart, span.sourceEnd) != span.sourceText ||
                span.hangul.isBlank()
            ) return WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS)
            val wordIndex = ranges.indexOfFirst { span.sourceStart in it }
            val endWordIndex = ranges.indexOfFirst { span.sourceEnd - 1 in it }
            if (wordIndex == -1 || endWordIndex == -1 || endWordIndex < wordIndex) {
                return WordAlignmentResult.LineWide(AlignmentFailure.BOUNDARY_SPLIT)
            }
            // A morphological span is an indivisible verified sound. Its native
            // presentation may cover more than one Apple karaoke cell; never
            // fabricate arbitrary Hangul syllable boundaries to fill those cells.
            if (wordIndex != endWordIndex) grouped = true
            sounds[wordIndex].append(span.hangul)
            previousEnd = span.sourceEnd
        }

        // No untranscribed letter may be silently treated as verified.
        val covered = BooleanArray(original.length)
        spans.forEach { span ->
            for (i in span.sourceStart until span.sourceEnd) covered[i] = true
        }
        if (original.indices.any { original[it].isLetterOrDigit() && !covered[it] }) {
            return WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS)
        }
        val joined = sounds.joinToString("") { it.toString() }.filter { it in '가'..'힣' }
        if (joined.isEmpty() || expectedHangul != null &&
            joined != expectedHangul.filter { it in '가'..'힣' }
        ) return WordAlignmentResult.LineWide(AlignmentFailure.SOUND_MISMATCH)
        val words = appleWords.mapIndexed { index, source ->
            LyricWordPronunciation(source, sounds[index].toString())
        }
        return if (grouped) WordAlignmentResult.Grouped(words)
        else WordAlignmentResult.Verified(words)
    }
}
