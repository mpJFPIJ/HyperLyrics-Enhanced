package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation

/**
 * Position-preserving Hangul projection onto Apple's existing main lyric words.
 * An Apple word receives Hangul only when the SOUND BOUNDARY belongs to that
 * word. A complete Hangul sentence does not establish any word boundary.
 *
 * This maps text to a host word cell, NOT a verified per-phoneme timestamp.
 * Apple retains full ownership of word start/end and its highlight animation.
 */
internal object AppleNativeReadingProjection {
    const val EMPTY_PRONUNCIATION_CELL = "\u200B"

    data class Result(val words: List<LyricWordPronunciation>, val approximate: Boolean)

    private fun validVerifiedSpan(span: SourceReadingSpan): Boolean =
        span.hangul.isNotBlank() && span.hangul.all {
            it in '가'..'힣' || it.isWhitespace()
        }

    /** Kotlin Char checks see neither half of a supplementary Han glyph as
     * a letter. Such a glyph is not an ignorable gap between Apple cells. */
    private fun containsSourceLetters(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            if (Character.isLetterOrDigit(codePoint)) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Prefer the native lyric word renderer so a wrapped line never becomes
     * several Japanese rows followed by one unrelated block of Hangul.
     *
     * A grouped Kanji token has no provable internal syllable boundary unless
     * all its other fragments are independently pronounceable kana, so the
     * single Kanji fragment owns the verified residual syllables. Complete
     * source spans or one isolated cell between anchors also use those clocks.
     * Otherwise let the measured line-wide/interleaved presentation own the
     * intact sound; never borrow a neighboring cell's syllables.
     */
    fun projectForNativeKaraoke(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        pronunciation: String,
    ): Result? {
        if (appleWords.none(::containsSourceLetters)) return null
        // Do not discard orphan jamo or unexpected script characters when
        // looking up an exact sound anchor: normalization is not verification.
        if (spans.any { !validVerifiedSpan(it) }) return null
        if (com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
                .sanitizeHangulSupplement(original, pronunciation) == null
        ) return null
        if (spans.isNotEmpty()) {
            project(original, appleWords, spans, pronunciation)?.let { return it }
        }
        // An entire native kana cell can be transcribed independently.
        // Use only uniquely located sounds that do not conflict with the
        // caller's independently supplied dictionary spans.
        val kanaAnchors = unambiguousKanaWordAnchors(original, appleWords, pronunciation)
        val additions = kanaAnchors.filter { kana ->
            spans.none { span ->
                kana.sourceStart < span.sourceEnd && kana.sourceEnd > span.sourceStart
            }
        }
        val anchors = (spans + additions).sortedBy { it.sourceStart }
        if (anchors.isEmpty()) return null
        val projected = projectAnchored(original, appleWords, anchors, pronunciation)
            ?: return null
        // A one-word track may render a fully verified reading naturally, but
        // cannot claim a timing proof when part of its sound was inferred from
        // a partial dictionary anchor. More than one native word retains the
        // legacy single-cell-gap inference, never a cross-word syllable split.
        if (appleWords.count(::containsSourceLetters) == 1 &&
            projected.approximate
        ) return null
        return projected
    }

    /** A unique Hangul substring is not proof that its kana source owns it.
     * Hiragana は/へ may be pronounced as particles (와/에), and a native cell
     * boundary can bisect a digraph, sokuon, nasal or lengthened vowel.
     * Without a verified source span, trust the cell only when the REAL
     * kana converter gives the same sound with the adjacent kana attached. */
    private fun hasContextIndependentKanaSound(
        original: String,
        start: Int,
        end: Int,
        reading: String,
    ): Boolean {
        if (start < 0 || end <= start || end > original.length) return false
        val source = original.substring(start, end)
        if (source.any { it !in '\u3040'..'\u30ff' || it == 'は' || it == 'へ' }) {
            return false
        }
        fun sound(value: String): String = JapaneseToHangul.fromKana(value)
            ?.filter { it in '가'..'힣' }.orEmpty()
        val ownSound = sound(source)
        if (ownSound.isEmpty() || ownSound != reading.filter { it in '가'..'힣' }) {
            return false
        }
        var left = start
        while (left > 0 && original[left - 1] in '\u3040'..'\u30ff') left--
        var right = end
        while (right < original.length && original[right] in '\u3040'..'\u30ff') right++
        val contextual = JapaneseToHangul.fromKana(original.substring(left, right))
            ?.filter { it in '가'..'힣' } ?: return false
        val separate = sound(original.substring(left, start)) + ownSound +
            sound(original.substring(end, right))
        return contextual == separate
    }

    private fun unambiguousKanaWordAnchors(
        original: String,
        appleWords: List<String>,
        pronunciation: String,
    ): List<SourceReadingSpan> {
        val sounded = pronunciation.filter { it in '가'..'힣' }
        if (sounded.isEmpty()) return emptyList()
        val spans = mutableListOf<SourceReadingSpan>()
        var sourceCursor = 0
        var readingCursor = 0
        for (native in appleWords) {
            val word = native.trim()
            if (word.isEmpty()) continue
            val sourceStart = original.indexOf(word, sourceCursor)
            if (sourceStart < 0 || containsSourceLetters(
                    original.substring(sourceCursor, sourceStart),
                )
            ) return emptyList()
            sourceCursor = sourceStart + word.length
            if (word.any { it !in '\u3040'..'\u30ff' } ||
                word.all { it == '\u30fc' || it == '\u30fb' }
            ) continue
            val kanaReading = JapaneseToHangul.fromKana(word) ?: continue
            val hangul = kanaReading.filter { it in '가'..'힣' }
            if (hangul.isEmpty() || !hasContextIndependentKanaSound(
                    original, sourceStart, sourceCursor, hangul,
                )
            ) continue
            val soundStart = sounded.indexOf(hangul, readingCursor)
            // A repeated same-sounding particle is not by itself evidence
            // of a unique phonetic offset in the full Korean line.
            if (soundStart < 0 || sounded.indexOf(hangul, soundStart + 1) >= 0) continue
            spans += SourceReadingSpan(
                sourceStart, sourceCursor, word, kanaReading, hangul,
            )
            readingCursor = soundStart + hangul.length
        }
        return spans
    }

    /** Compatibility entry point for unspanned lines. The previous
     * implementation proportionally divided Hangul by Japanese glyph counts,
     * assigning e.g. part of 溺れ to the following native word. Unverified
     * splits are no longer accepted, even through this legacy entry point. */
    fun projectApproximate(
        original: String,
        appleWords: List<String>,
        pronunciation: String,
    ): Result? = projectForNativeKaraoke(original, appleWords, emptyList(), pronunciation)

    /**
     * Project verified phonetic spans and uniquely attributed residual gaps.
     * An unresolved SOUND GAP may be assigned only if it intersects exactly
     * ONE native source word: the sounds remain uncertain, but their word
     * boundary is not divided. An unverified gap over two words, or any
     * unsplittable verified Kanji compound, must use line-wide rendering.
     */
    fun projectAnchored(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        pronunciation: String,
    ): Result? {
        if (spans.isEmpty() || appleWords.isEmpty() ||
            spans.any { !validVerifiedSpan(it) } ||
            com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
                .sanitizeHangulSupplement(original, pronunciation) == null
        ) return null
        val full = pronunciation.filter { it in '가'..'힣' }
        if (full.isEmpty() || pronunciation.any {
                it.isLetter() && it !in '가'..'힣'
            }) return null
        val ranges = mutableListOf<IntRange>()
        var sourceCursor = 0
        for (word in appleWords) {
            val token = word.trim()
            if (token.isEmpty()) return null
            val start = original.indexOf(token, sourceCursor)
            if (start < sourceCursor || start < 0 ||
                containsSourceLetters(original.substring(sourceCursor, start))
            ) return null
            ranges += start until start + token.length
            sourceCursor = start + token.length
        }
        if (containsSourceLetters(original.substring(sourceCursor))) return null
        data class Piece(val start: Int, val end: Int, val reading: String,
            val independentlyVerified: Boolean)
        val pieces = mutableListOf<Piece>()
        var sourceOffset = 0
        var soundOffset = 0
        for (span in spans) {
            if (span.sourceStart < sourceOffset || span.sourceEnd > original.length ||
                span.sourceEnd <= span.sourceStart ||
                original.substring(span.sourceStart, span.sourceEnd) != span.sourceText ||
                !containsSourceLetters(span.sourceText)
            ) return null
            val anchor = span.hangul.filter { it in '가'..'힣' }
            if (anchor.isEmpty()) return null
            val soundStart = full.indexOf(anchor, soundOffset)
            if (soundStart < 0) return null
            // A repeated anchor appearing after an untranscribed gap cannot
            // prove which occurrence corresponds to its source substring.
            if (soundStart > soundOffset &&
                full.indexOf(anchor, soundStart + 1) >= 0
            ) return null
            if (span.sourceStart == sourceOffset && soundStart != soundOffset) return null
            if (span.sourceStart > sourceOffset) {
                pieces += Piece(
                    sourceOffset, span.sourceStart,
                    full.substring(soundOffset, soundStart),
                    independentlyVerified = false,
                )
            }
            pieces += Piece(span.sourceStart, span.sourceEnd, anchor,
                independentlyVerified = true)
            sourceOffset = span.sourceEnd
            soundOffset = soundStart + anchor.length
        }
        if (sourceOffset < original.length) {
            pieces += Piece(sourceOffset, original.length, full.substring(soundOffset),
                independentlyVerified = false)
        } else if (soundOffset != full.length) return null

        // A standalone length mark may be genuinely silent, but only when
        // independently converting the WHOLE kana line confirms its sound.
        val kanaSound = if (original.all { it in '\u3040'..'\u30ff' ||
                    it.isWhitespace()
            }) JapaneseToHangul.fromKana(original)?.filter { it in '가'..'힣' }
            else null
        val verifiedSilentKana = kanaSound == full
        val readings = List(appleWords.size) { StringBuilder() }
        var inferredCell = false
        for (piece in pieces) {
            val overlaps = ranges.mapIndexedNotNull { index, range ->
                val left = maxOf(range.first, piece.start)
                val right = minOf(range.last + 1, piece.end)
                if (right > left) index to (left until right) else null
            }
            val fragment = original.substring(piece.start, piece.end)
            val soundedSource = containsSourceLetters(fragment)
            // A punctuation-only gap is silent. A complete but inconsistent
            // AI sentence cannot turn an exclamation mark into a sung word.
            if (piece.reading.isNotEmpty() && !soundedSource) return null
            // Literal kana has independently knowable sound, unlike Kanji.
            // Do not label e.g. こ as 카 merely because one native clock is
            // left between two verified anchors. Context-dependent particles
            // and non-additive kana fail closed if the literal read disagrees.
            val literalKanaGap = !piece.independentlyVerified &&
                piece.reading.isNotEmpty() &&
                soundedSource && fragment.all { it in '\u3040'..'\u30ff' }
            if (literalKanaGap && !hasContextIndependentKanaSound(
                    original, piece.start, piece.end, piece.reading,
                )
            ) return null
            val verifiedLiteralKana = literalKanaGap
            if (overlaps.isEmpty()) {
                // Apple's word vector may omit punctuation/whitespace, never
                // a source letter or a phonetic syllable.
                if (piece.reading.isNotEmpty() || containsSourceLetters(fragment))
                    return null
                continue
            }
            if (piece.reading.isEmpty()) {
                if (containsSourceLetters(fragment) &&
                    !(verifiedSilentKana && fragment.all { it == 'ー' })
                ) return null
                continue
            }
            if (overlaps.size == 1) {
                readings[overlaps.single().first].append(piece.reading)
                if (!piece.independentlyVerified && !verifiedLiteralKana) inferredCell = true
                continue
            }
            // Multiple native clocks share a verified token only when exact
            // independently converted kana fragments prove the boundaries,
            // optionally isolating one Kanji-containing residual fragment.
            // Two Kanji unknowns, digraph halves, and incompatible kana fail.
            if (!piece.independentlyVerified) return null
            val sounds = VerifiedJapaneseFragmentReading.split(
                overlaps.map { (_, range) -> original.substring(range.first, range.last + 1) },
                piece.reading,
            ) ?: return null
            overlaps.forEachIndexed { index, (wordIndex, _) ->
                readings[wordIndex].append(sounds[index])
            }
        }
        val aligned = appleWords.mapIndexed { index, word ->
            LyricWordPronunciation(
                word, readings[index].toString().ifEmpty { EMPTY_PRONUNCIATION_CELL },
            )
        }
        if (aligned.joinToString("") { it.hangul }.filter { it in '가'..'힣' } != full) {
            return null
        }
        // The isolated gap's sound may be unverified, but no syllable has
        // been divided between two distinct native word clocks.
        return Result(aligned, approximate = inferredCell)
    }

    fun project(
        original: String,
        appleWords: List<String>,
        spans: List<SourceReadingSpan>,
        expectedHangul: String,
    ): Result? {
        if (spans.any { !validVerifiedSpan(it) }) return null
        val verified = AppleWordReadingAlignment.align(
            original, appleWords, spans, expectedHangul,
        ) as? WordAlignmentResult.Verified ?: return null
        // Grouped means one *verified* Japanese phonetic token crosses several
        // Apple cells. Its Hangul sound is verified as a whole, but no
        // syllable-to-cell breakpoint is. Dividing by source glyph count
        // fabricates a karaoke boundary and may leave its last syllable at
        // the next flexbox row. Keep the intact line-wide reading instead.
        val words = verified.words.map { word ->
            if (word.hangul.isNotEmpty()) word else {
                // The alignment has already proved source AND sound offsets.
                // Only punctuation cells may remain silent; keep Apple from
                // rendering their original glyph as a pronunciation fallback.
                if (containsSourceLetters(word.source)) return null
                word.copy(hangul = EMPTY_PRONUNCIATION_CELL)
            }
        }
        val merged = words.joinToString("") { it.hangul }.filter { it in '가'..'힣' }
        if (merged != expectedHangul.filter { it in '가'..'힣' }) return null
        return Result(words, approximate = false)
    }
}
