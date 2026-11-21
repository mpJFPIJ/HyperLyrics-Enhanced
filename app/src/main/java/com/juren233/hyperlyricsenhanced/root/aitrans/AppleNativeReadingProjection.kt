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

    /**
     * Last-resort karaoke-cell mapping when the song has a complete Hangul
     * line but Sudachi/Apple source spans are unavailable. This is an
     * explicit approximation: original native word timestamps remain intact.
     * Reject unmatched source lines to prevent recycled-holder leakage.
     */
    fun projectApproximate(
        original: String,
        appleWords: List<String>,
        pronunciation: String,
    ): Result? {
        if (appleWords.isEmpty() ||
            com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
                .sanitizeHangulSupplement(original, pronunciation) == null
        ) return null
        fun compact(text: String): String = text.filter(Char::isLetterOrDigit)
        if (compact(original).isEmpty() ||
            compact(original) != compact(appleWords.joinToString(""))
        ) return null
        val syllables = pronunciation.filter { it in '가'..'힣' }
        if (syllables.isEmpty() || pronunciation.any {
                it.isLetter() && it !in '가'..'힣'
            }) return null
        val weights = appleWords.map { word -> word.count(Char::isLetterOrDigit) }
        val total = weights.sum()
        if (total == 0) return null
        var accumulatedWeight = 0
        var consumed = 0
        val words = appleWords.mapIndexed { index, source ->
            val start = accumulatedWeight * syllables.length / total
            accumulatedWeight += weights[index]
            val end = accumulatedWeight * syllables.length / total
            // Source punctuation and long-vowel marks may have no separate
            // spoken syllable. Use a non-rendering placeholder instead of
            // exposing the original Japanese glyph or duplicating a sound.
            val reading = syllables.substring(start, end)
                .ifEmpty { EMPTY_PRONUNCIATION_CELL }
            consumed += end - start
            LyricWordPronunciation(source, reading)
        }
        if (consumed != syllables.length ||
            words.joinToString("") { it.hangul }.filter { it in '가'..'힣' } != syllables
        ) return null
        return Result(words, approximate = true)
    }

    /**
     * Use verified Sudachi fragments as anchors inside a complete AI phonetic
     * line. The old incomplete-analyzer path discarded those anchors, then
     * divided every Hangul syllable by Japanese character counts. That can
     * delay a complete word until the following Apple karaoke cell.
     *
     * Source anchors are never guessed: an anchor's actual source substring
     * and Hangul reading must both match in order. Only the unresolved gaps
     * are proportional, and every original native cell keeps its own timing.
     * An unavailable or inconsistent anchor fails closed to the existing
     * approximate fallback.
     */
    fun projectAnchored(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        pronunciation: String,
    ): Result? {
        if (spans.isEmpty() || appleWords.isEmpty()) return null
        val baseline = projectApproximate(original, appleWords, pronunciation)
            ?: return null
        val full = pronunciation.filter { it in '가'..'힣' }
        val ranges = mutableListOf<IntRange>()
        var sourceCursor = 0
        for (word in appleWords) {
            val token = word.trim()
            if (token.isEmpty()) return null
            val start = original.indexOf(token, sourceCursor)
            if (start < sourceCursor || start < 0 ||
                original.substring(sourceCursor, start).any(Char::isLetterOrDigit)
            ) return null
            ranges += start until start + token.length
            sourceCursor = start + token.length
        }
        data class Piece(val start: Int, val end: Int, val reading: String)
        val pieces = mutableListOf<Piece>()
        var sourceOffset = 0
        var soundOffset = 0
        for (span in spans.sortedBy { it.sourceStart }) {
            if (span.sourceStart < sourceOffset || span.sourceEnd > original.length ||
                span.sourceEnd <= span.sourceStart ||
                original.substring(span.sourceStart, span.sourceEnd) != span.sourceText
            ) return null
            val anchor = span.hangul.filter { it in '가'..'힣' }
            if (anchor.isEmpty()) return null
            val soundStart = full.indexOf(anchor, soundOffset)
            if (soundStart < 0) return null
            if (soundStart > soundOffset) {
                if (span.sourceStart <= sourceOffset) return null
                pieces += Piece(
                    sourceOffset, span.sourceStart,
                    full.substring(soundOffset, soundStart),
                )
            }
            pieces += Piece(span.sourceStart, span.sourceEnd, anchor)
            sourceOffset = span.sourceEnd
            soundOffset = soundStart + anchor.length
        }
        if (soundOffset < full.length) {
            if (sourceOffset >= original.length) return null
            pieces += Piece(sourceOffset, original.length, full.substring(soundOffset))
        }

        val readings = List(appleWords.size) { StringBuilder() }
        for (piece in pieces) {
            val overlaps = ranges.mapIndexedNotNull { index, range ->
                val width = (minOf(range.last + 1, piece.end) -
                    maxOf(range.first, piece.start)).coerceAtLeast(0)
                if (width > 0) index to width else null
            }
            if (overlaps.isEmpty()) return null
            val units = piece.reading.length
            val weight = overlaps.sumOf { it.second }
            var consumed = 0
            var weighted = 0
            overlaps.forEachIndexed { order, (index, width) ->
                weighted += width
                val next = if (order == overlaps.lastIndex) units
                    else weighted * units / weight
                if (next < consumed) return null
                readings[index].append(piece.reading.substring(consumed, next))
                consumed = next
            }
            if (consumed != units) return null
        }
        val aligned = appleWords.mapIndexed { index, word ->
            LyricWordPronunciation(
                word, readings[index].toString().ifEmpty { EMPTY_PRONUNCIATION_CELL },
            )
        }
        if (aligned.joinToString("") { it.hangul }.filter { it in '가'..'힣' } != full) {
            return null
        }
        // A partial dictionary analysis cannot prove the gap's phonetic
        // boundaries. Be explicit; source words still have authentic clocks.
        return Result(aligned, approximate = baseline.approximate)
    }

    fun project(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        expectedHangul: String,
    ): Result? {
        val valid = AppleWordReadingAlignment.align(
            original, appleWords, spans, expectedHangul,
        )
        // Grouped means one *verified* Japanese phonetic token crosses several
        // Apple cells. Its Hangul sound is verified as a whole, but no
        // syllable-to-cell breakpoint is. Dividing by source glyph count
        // fabricates a karaoke boundary and may leave its last syllable at
        // the next flexbox row. Keep the intact line-wide reading instead.
        if (valid !is WordAlignmentResult.Verified) return null
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
