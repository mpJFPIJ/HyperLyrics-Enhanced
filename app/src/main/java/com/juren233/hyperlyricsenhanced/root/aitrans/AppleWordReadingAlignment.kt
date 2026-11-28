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
    private fun isCodePointBoundary(source: String, offset: Int): Boolean =
        offset in 0..source.length && !(offset > 0 && offset < source.length &&
            Character.isHighSurrogate(source[offset - 1]) &&
            Character.isLowSurrogate(source[offset])) &&
            (offset == source.length || !JapaneseToHangul.isJapaneseVoicingMark(
                Character.codePointAt(source, offset),
            ))

    private fun containsLetters(source: String): Boolean {
        var offset = 0
        while (offset < source.length) {
            val cp = Character.codePointAt(source, offset)
            if (JapaneseToHangul.isSpokenSourceBase(cp)) return true
            offset += Character.charCount(cp)
        }
        return false
    }

    private fun hasUncoveredLetters(source: String, covered: BooleanArray): Boolean {
        var offset = 0
        while (offset < source.length) {
            val cp = Character.codePointAt(source, offset)
            val end = offset + Character.charCount(cp)
            if (JapaneseToHangul.isLyricSourceContent(cp) &&
                (offset until end).any { !covered[it] }
            ) return true
            offset = end
        }
        return false
    }

    private fun isUnspokenDelimiter(char: Char): Boolean {
        if (char.isWhitespace()) return true
        return when (Character.getType(char)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    fun align(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        expectedHangul: String? = null,
    ): WordAlignmentResult {
        if (appleWords.isEmpty()) return WordAlignmentResult.LineWide(AlignmentFailure.NO_WORDS)
        if (spans.isEmpty()) return WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS)
        if (expectedHangul != null && !JapaneseToHangul.onlyHangulSoundLetters(expectedHangul))
            return WordAlignmentResult.LineWide(AlignmentFailure.SOUND_MISMATCH)

        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (word in appleWords) {
            val value = word.trim()
            if (value.isEmpty() || value.contains('<') || value.contains('>')) {
                return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
            }
            // Preserve punctuation when Apple includes it as a karaoke word;
            // skip it only if Apple omitted the mark from the native vector.
            while (cursor < original.length && original[cursor].isWhitespace()) cursor++
            if (!original.startsWith(value, cursor)) {
                while (cursor < original.length && isUnspokenDelimiter(original[cursor])) cursor++
            }
            if (!original.startsWith(value, cursor)) {
                return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
            }
            if (!isCodePointBoundary(original, cursor) ||
                !isCodePointBoundary(original, cursor + value.length)
            ) return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
            ranges += cursor until (cursor + value.length)
            cursor += value.length
        }
        if (original.substring(cursor).any { !isUnspokenDelimiter(it) }) {
            return WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH)
        }

        val sounds = MutableList(appleWords.size) { StringBuilder() }
        var previousEnd = 0
        var grouped = false
        for (span in spans) {
            if (span.sourceStart < previousEnd || span.sourceEnd <= span.sourceStart ||
                span.sourceEnd > original.length ||
                !isCodePointBoundary(original, span.sourceStart) ||
                !isCodePointBoundary(original, span.sourceEnd) ||
                original.substring(span.sourceStart, span.sourceEnd) != span.sourceText ||
                span.hangul.isBlank() ||
                span.hangul.any { it !in '가'..'힣' && !it.isWhitespace() } ||
                // Punctuation may occupy a native lyric cell, but it cannot
                // own phonetic syllables or inherit a sung-word clock.
                !containsLetters(span.sourceText)
            ) return WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS)
            val wordIndex = ranges.indexOfFirst { span.sourceStart in it }
            val endWordIndex = ranges.indexOfFirst { span.sourceEnd - 1 in it }
            if (wordIndex == -1 || endWordIndex == -1 || endWordIndex < wordIndex) {
                return WordAlignmentResult.LineWide(AlignmentFailure.BOUNDARY_SPLIT)
            }
            if (wordIndex == endWordIndex) {
                sounds[wordIndex].append(span.hangul)
            } else {
                // The very same phonetic boundary proof is used for Apple's
                // timed cells and measured visual rows. An isolated Kanji
                // fragment may receive the exact residual of a verified token
                // when the neighboring kana fragments establish its edges.
                val fragments = (wordIndex..endWordIndex).map { index ->
                    val range = ranges[index]
                    val start = maxOf(span.sourceStart, range.first)
                    val end = minOf(span.sourceEnd, range.last + 1)
                    original.substring(start, end)
                }
                val proven = VerifiedJapaneseFragmentReading.split(
                    fragments, span.hangul.filter { it in '가'..'힣' },
                )?.takeIf { span.hangul.all { it in '가'..'힣' || it.isWhitespace() } }
                if (proven != null) {
                    proven.forEachIndexed { index, sound ->
                        sounds[wordIndex + index].append(sound)
                    }
                } else {
                    grouped = true
                    sounds[wordIndex].append(span.hangul)
                }
            }
            previousEnd = span.sourceEnd
        }

        // No untranscribed letter may be silently treated as verified.
        val covered = BooleanArray(original.length)
        spans.forEach { span ->
            for (i in span.sourceStart until span.sourceEnd) covered[i] = true
        }
        if (hasUncoveredLetters(original, covered)) {
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
