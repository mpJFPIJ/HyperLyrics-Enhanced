package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation

/**
 * Position-preserving Hangul projection onto Apple's existing main lyric words.
 * Exact whole-token matches are copied. A token split by Apple's cells is
 * divided only at Hangul syllable boundaries, and marked approximate.
 *
 * This maps text to a host word cell, NOT a verified per-phoneme timestamp.
 * Apple retains full ownership of word start/end and its highlight animation.
 */
internal object AppleNativeReadingProjection {
    const val EMPTY_PRONUNCIATION_CELL = "\u200B"

    data class Result(val words: List<LyricWordPronunciation>, val approximate: Boolean)

    fun project(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        expectedHangul: String,
    ): Result? {
        val valid = AppleWordReadingAlignment.align(
            original, appleWords, spans, expectedHangul,
        )
        if (valid is WordAlignmentResult.LineWide) return null
        if (appleWords.isEmpty() || spans.isEmpty()) return null
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (sourceWord in appleWords) {
            val value = sourceWord.trim()
            if (value.isEmpty()) return null
            val found = original.indexOf(value, cursor)
            if (found < cursor || found < 0 ||
                original.substring(cursor, found).any { it.isLetterOrDigit() }
            ) return null
            ranges += found until (found + value.length)
            cursor = found + value.length
        }
        val texts = List(appleWords.size) { StringBuilder() }
        var approximate = false
        for (span in spans) {
            val syllables = span.hangul.filter { it in '가'..'힣' }
            if (syllables.isEmpty()) return null
            val overlaps = ranges.mapIndexedNotNull { index, range ->
                val length = (minOf(range.last + 1, span.sourceEnd) -
                    maxOf(range.first, span.sourceStart)).coerceAtLeast(0)
                if (length == 0) null else index to length
            }
            if (overlaps.isEmpty() || syllables.length < overlaps.size) return null
            if (overlaps.size == 1) {
                texts[overlaps[0].first].append(syllables)
                continue
            }
            approximate = true
            // Reserve one complete Hangul syllable per source cell and place
            // extra syllables in source-character proportion. Never split jamo
            // or duplicate/drop the source phonetic text.
            val total = overlaps.sumOf { it.second }
            val extras = syllables.length - overlaps.size
            var used = 0
            var weighted = 0
            overlaps.forEachIndexed { order, (index, sourceLength) ->
                val startExtra = weighted * extras / total
                weighted += sourceLength
                val endExtra = weighted * extras / total
                val count = if (order == overlaps.lastIndex)
                    syllables.length - used else 1 + endExtra - startExtra
                if (count <= 0 || used + count > syllables.length) return null
                texts[index].append(syllables.substring(used, used + count))
                used += count
            }
            if (used != syllables.length) return null
        }
        val words = appleWords.mapIndexed { index, source ->
            val sound = texts[index].toString().ifEmpty {
                // Some native words are punctuation-only. An invisible word
                // joiner prevents Apple from showing source Japanese text in
                // an empty pronunciation cell; it has no audible pronunciation.
                if (source.none(Char::isLetterOrDigit)) EMPTY_PRONUNCIATION_CELL
                else return null
            }
            LyricWordPronunciation(source, sound)
        }
        val merged = words.joinToString("") { it.hangul }.filter { it in '가'..'힣' }
        if (merged != expectedHangul.filter { it in '가'..'힣' }) return null
        return Result(words, approximate)
    }
}
