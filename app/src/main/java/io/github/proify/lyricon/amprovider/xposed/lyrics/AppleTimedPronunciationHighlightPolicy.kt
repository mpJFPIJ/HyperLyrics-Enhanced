package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.root.aitrans.JapaneseToHangul

/** Read-only copies of Apple Music's actual main-word timing values, in milliseconds. */
internal data class AppleTimedSourceWord(
    val text: String,
    val beginMs: Long,
    val endMs: Long,
)

/** Original UTF-16 offsets are inherited from the locally validated Japanese tokenizer. */
internal data class ApplePronunciationSourceToken(
    val sourceStart: Int,
    val sourceEnd: Int,
    val hangul: String,
    val sourceText: String? = null,
)

/** An entire verified phonetic token, or a coarse native line interval. */
internal data class AppleTimedReadingUnit(
    val start: Int,
    val end: Int,
    val beginMs: Long,
    val endMs: Long,
    // Original visible native word index; -1 if several words or a line share this window.
    val sourceWordIndex: Int = -1,
    val isWholeLine: Boolean = false,
    // A word with one native clock may occupy two *verified* visual rows.
    // Retain its entire Hangul reading for a continuous pixel-width sweep;
    // sweepStartOffset locates this row's first character within that word.
    val sweepFullReading: String? = null,
    val sweepStartOffset: Int = 0,
)

/** Never fabricate Korean syllable boundaries from Japanese character lengths. */
internal object AppleTimedPronunciationHighlightPolicy {
    private data class LocatedWord(
        val sourceStart: Int,
        val sourceEnd: Int,
        val beginMs: Long,
        val endMs: Long,
        val nativeIndex: Int,
    )

    private data class LocatedSound(
        val sourceStart: Int,
        val sourceEnd: Int,
        val readingStart: Int,
        val readingEnd: Int,
    )

    /** Keep complete Unicode code points while retaining UTF-16 source offsets.
     * Char.isLetterOrDigit() silently drops supplementary Japanese Han glyphs. */
    private fun contentOffsets(text: String): List<Int> = buildList {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (appleIsLyricSourceCodePoint(codePoint)) {
                for (part in 0 until width) add(index + part)
            }
            index += width
        }
    }

    private fun contentText(text: String): String = appleLyricSourceKey(text)

    // Both passes respect order. If the earliest and latest legal placements
    // disagree, a repeated word cannot be attributed to one occurrence.
    private fun uniqueOffsets(text: String, parts: List<String>): List<Int?> {
        var cursor = 0
        val first = IntArray(parts.size)
        for ((index, part) in parts.withIndex()) {
            val start = text.indexOf(part, cursor)
            if (start < 0) return List(parts.size) { null }
            first[index] = start
            cursor = start + part.length
        }
        val offsets = MutableList<Int?>(parts.size) { null }
        cursor = text.length
        for (index in parts.indices.reversed()) {
            val part = parts[index]
            val start = text.lastIndexOf(part, cursor - part.length)
            if (start == first[index]) offsets[index] = start
            cursor = start
        }
        return offsets
    }

    private fun locateWords(source: String, words: List<AppleTimedSourceWord>): List<LocatedWord> {
        val content = words.mapIndexedNotNull { index, word ->
            val letters = contentText(word.text)
            if (letters.isEmpty()) null else index to letters
        }
        // Untimed cells still occupy source text and native view indices.
        val offsets = uniqueOffsets(source, content.map { it.second })
        return content.mapIndexedNotNull { index, (nativeIndex, letters) ->
            val start = offsets[index] ?: return@mapIndexedNotNull null
            val word = words[nativeIndex]
            if (word.beginMs < 0 || word.endMs <= word.beginMs) return@mapIndexedNotNull null
            LocatedWord(start, start + letters.length, word.beginMs, word.endMs, nativeIndex)
        }
    }

    private fun locateSounds(
        source: String, readingLetters: String, spans: List<ApplePronunciationSourceToken>,
    ): List<LocatedSound> {
        if (!appleOnlyHangulSoundLetters(readingLetters) ||
            spans.any { !appleOnlyHangulSoundLetters(it.hangul) }
        ) return emptyList()
        val sourceOffsets = IntArray(source.length + 1)
        val validBoundary = BooleanArray(source.length + 1) { true }
        var sourceIndex = 0
        while (sourceIndex < source.length) {
            val codePoint = source.codePointAt(sourceIndex)
            val width = Character.charCount(codePoint)
            val accepted = appleIsLyricSourceCodePoint(codePoint)
            if (width == 2) validBoundary[sourceIndex + 1] = false
            if (appleIsJapaneseVoicingMark(codePoint)) validBoundary[sourceIndex] = false
            for (part in 1..width) {
                sourceOffsets[sourceIndex + part] = sourceOffsets[sourceIndex] +
                    (if (accepted) part else 0)
            }
            sourceIndex += width
        }
        var previousEnd = 0
        val parts = spans.map { span ->
            if (span.sourceStart < previousEnd || span.sourceEnd <= span.sourceStart ||
                span.sourceEnd > source.length ||
                !validBoundary[span.sourceStart] || !validBoundary[span.sourceEnd] ||
                // A source-less token carries no proof that its word clock
                // belongs to this lyric, even when its sound happens to fit.
                span.sourceText == null ||
                source.substring(span.sourceStart, span.sourceEnd) != span.sourceText ||
                !appleHasSpokenSourceBase(span.sourceText) ||
                sourceOffsets[span.sourceStart] == sourceOffsets[span.sourceEnd]
            ) return emptyList()
            previousEnd = span.sourceEnd
            contentText(span.hangul).ifEmpty { return emptyList() }
        }
        val offsets = uniqueOffsets(readingLetters, parts)
        return spans.mapIndexedNotNull { i, span ->
            val start = offsets[i] ?: return@mapIndexedNotNull null
            LocatedSound(sourceOffsets[span.sourceStart], sourceOffsets[span.sourceEnd],
                start, start + parts[i].length)
        }
    }

    private fun literalKanaSounds(
        source: String, reading: String, words: List<AppleTimedSourceWord>,
    ): List<LocatedSound> {
        if (!AppleInterleavedReadingPolicy.canDeriveKanaRows(source, reading)) return emptyList()
        val sourceLetters = contentText(source)
        val readingLetters = contentText(reading)
        var sourceCursor = 0
        var readingCursor = 0
        val sounds = mutableListOf<LocatedSound>()
        for (word in words) {
            val letters = contentText(word.text)
            if (letters.isEmpty()) continue
            if (!sourceLetters.startsWith(letters, sourceCursor)) return emptyList()
            val sound = JapaneseToHangul.fromKana(word.text)?.filter(Char::isLetterOrDigit)
            if (sound.isNullOrEmpty() || !readingLetters.startsWith(sound, readingCursor)) {
                return emptyList()
            }
            sounds += LocatedSound(sourceCursor, sourceCursor + letters.length,
                readingCursor, readingCursor + sound.length)
            sourceCursor += letters.length
            readingCursor += sound.length
        }
        // Requiring complete coverage also disambiguates repeated native words.
        return if (sourceCursor == sourceLetters.length && readingCursor == readingLetters.length)
            sounds else emptyList()
    }

    // A dictionary token can contain more than one native word, even across
    // a real rest. Its original sound anchors both ends of any finer mapping.
    // A mixed Kanji+kana token can split at a real native word rest only if
    // the literal kana independently determines *every* remaining syllable.
    // Continuous native words keep their original combined token clock.
    private fun splitVerifiedKana(
        source: String,
        readingLetters: String,
        sounds: List<LocatedSound>,
        words: List<LocatedWord>,
        nativeWords: List<AppleTimedSourceWord>,
    ): List<LocatedSound> {
        val wordsByStart = words.associateBy { it.sourceStart }
        val sourceOffsets = contentOffsets(source)
        return sounds.flatMap { sound ->
            val first = wordsByStart[sound.sourceStart]
            if (first == null || first.sourceEnd >= sound.sourceEnd) return@flatMap listOf(sound)
            val cells = mutableListOf<LocatedWord>()
            var cursor = sound.sourceStart
            while (cursor < sound.sourceEnd) {
                val word = wordsByStart[cursor] ?: return@flatMap listOf(sound)
                if (word.sourceEnd > sound.sourceEnd) return@flatMap listOf(sound)
                cells += word
                cursor = word.sourceEnd
            }
            val nativeCells = cells.map { nativeWords[it.nativeIndex] }
            val fragment = source.substring(sourceOffsets[sound.sourceStart],
                sourceOffsets[sound.sourceEnd - 1] + 1)
            val expected = readingLetters.substring(sound.readingStart, sound.readingEnd)
            val finer = literalKanaSounds(fragment, expected, nativeCells)
            if (finer.isNotEmpty()) finer.map { local ->
                LocatedSound(sound.sourceStart + local.sourceStart, sound.sourceStart + local.sourceEnd,
                    sound.readingStart + local.readingStart, sound.readingStart + local.readingEnd)
            } else if (cells.zipWithNext().all { (left, right) ->
                    right.beginMs >= left.endMs && right.endMs >= left.endMs
                } && cells.zipWithNext().any { (left, right) ->
                    right.beginMs > left.endMs
                }
            ) {
                val verified = AppleInterleavedReadingPolicy.verifiedFragmentSounds(
                    nativeCells.map { contentText(it.text) }, expected,
                ) ?: return@flatMap listOf(sound)
                var readingCursor = sound.readingStart
                cells.zip(verified).map { (word, token) ->
                    val start = readingCursor
                    readingCursor += token.length
                    LocatedSound(word.sourceStart, word.sourceEnd, start, readingCursor)
                }.takeIf { readingCursor == sound.readingEnd } ?: listOf(sound)
            } else {
                listOf(sound)
            }
        }
    }

    // Advance the shorter source interval until BOTH representations end at
    // the same boundary. This also covers several dictionary tokens in one
    // native cell. Missing source letters, reading letters or clocks break a group.
    private fun verifiedUnits(
        words: List<LocatedWord>, sounds: List<LocatedSound>, readingOffsets: List<Int>,
    ): List<AppleTimedReadingUnit> {
        val wordsByStart = words.associateBy { it.sourceStart }
        val soundsByStart = sounds.associateBy { it.sourceStart }
        val units = mutableListOf<AppleTimedReadingUnit>()
        for (firstSound in sounds) {
            val firstWord = wordsByStart[firstSound.sourceStart] ?: continue
            var word = firstWord
            var sound = firstSound
            while (sound.sourceEnd != word.sourceEnd) {
                if (sound.sourceEnd < word.sourceEnd) {
                    val next = soundsByStart[sound.sourceEnd] ?: break
                    if (next.readingStart != sound.readingEnd) break
                    sound = next
                } else {
                    val next = wordsByStart[word.sourceEnd] ?: break
                    // Never infer a continuous token clock across a native rest
                    // or a reversed timestamp.
                    if (next.beginMs < word.beginMs || next.beginMs > word.endMs ||
                        next.endMs < word.endMs
                    ) break
                    word = next
                }
            }
            if (word.sourceEnd == sound.sourceEnd) {
                units += AppleTimedReadingUnit(
                    start = readingOffsets[firstSound.readingStart],
                    end = readingOffsets[sound.readingEnd - 1] + 1,
                    beginMs = firstWord.beginMs,
                    endMs = word.endMs,
                    sourceWordIndex = if (firstWord === word) word.nativeIndex else -1,
                )
            }
        }
        return units
    }

    fun units(
        sourceText: String,
        pronunciation: String,
        timedWords: List<AppleTimedSourceWord>,
        phoneticSpans: List<ApplePronunciationSourceToken> = emptyList(),
        lineBeginMs: Long? = null,
        lineEndMs: Long? = null,
    ): List<AppleTimedReadingUnit> {
        val source = contentText(sourceText)
        val readingOffsets = contentOffsets(pronunciation)
        if (source.isEmpty() || readingOffsets.isEmpty()) return emptyList()
        val words = locateWords(source, timedWords)
        if (words.isNotEmpty()) {
            val sounds = if (phoneticSpans.isEmpty()) {
                literalKanaSounds(sourceText, pronunciation, timedWords)
            } else {
                val letters = contentText(pronunciation)
                val anchored = locateSounds(sourceText, letters, phoneticSpans)
                splitVerifiedKana(sourceText, letters, anchored, words, timedWords)
            }
            val verified = verifiedUnits(words, sounds, readingOffsets)
            if (verified.isNotEmpty()) return verified
        }

        val hasLineWindow = lineBeginMs != null && lineEndMs != null &&
            lineBeginMs >= 0L && lineEndMs > lineBeginMs
        if (!hasLineWindow && words.sumOf { it.sourceEnd - it.sourceStart } != source.length) {
            return emptyList()
        }
        val begin = if (hasLineWindow) lineBeginMs!! else words.minOf { it.beginMs }
        val end = if (hasLineWindow) lineEndMs!! else words.maxOf { it.endMs }
        return listOf(AppleTimedReadingUnit(
            start = readingOffsets.first(),
            end = readingOffsets.last() + 1,
            beginMs = begin,
            endMs = end,
            isWholeLine = true,
        ))
    }

    fun progress(positionMs: Long, unit: AppleTimedReadingUnit): Float = when {
        positionMs <= unit.beginMs -> 0f
        positionMs >= unit.endMs -> 1f
        else -> (positionMs - unit.beginMs).toFloat() /
            (unit.endMs - unit.beginMs).toFloat()
    }

    /** The actual speaking intervals are intentionally sparse. The scheduler
     * may slow down between native words, but the already sung reading glyphs
     * must remain painted during that rest. */
    private inline fun uniqueVariant(
        variants: List<List<AppleTimedReadingUnit>>,
        matches: (List<AppleTimedReadingUnit>) -> Boolean,
    ): Int? {
        var found = -1
        for (index in variants.indices) {
            if (!matches(variants[index])) continue
            // Repeated choruses are separate native occurrences. When both
            // claim this playback position, their ordering in a cached list
            // is not source-clock evidence of which one owns the glyphs.
            if (found != -1) return null
            found = index
        }
        return found.takeIf { it >= 0 }
    }

    fun speakingVariant(
        positionMs: Long,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Int? = uniqueVariant(variants) { timed ->
        // A partial verified map can contain an unknown interval between two
        // real source words. It is not evidence of continuous singing.
        hasSpeakingUnit(positionMs, timed)
    }

    /** Sampling cadence only needs evidence that *some* native word speaks.
     * Unlike foreground ownership it must remain true for overlapping
     * ambiguous chorus clocks, so paint can recover promptly when they part. */
    fun hasSpeakingUnit(positionMs: Long, units: List<AppleTimedReadingUnit>): Boolean =
        units.any { positionMs >= it.beginMs && positionMs < it.endMs }

    /** This also runs in the visible 16-ms paint pulse. `zipWithNext()`
     * constructed a new List<Pair> for every repeated chorus on every
     * sample, although validating successive native clocks needs no list. */
    private fun orderedNativeClocks(timed: List<AppleTimedReadingUnit>): Boolean {
        if (timed.isEmpty()) return false
        var previousBegin = Long.MIN_VALUE
        var previousEnd = Long.MIN_VALUE
        for (unit in timed) {
            if (unit.beginMs < 0L || unit.endMs <= unit.beginMs ||
                unit.beginMs < previousBegin || unit.endMs < previousEnd
            ) return false
            previousBegin = unit.beginMs
            previousEnd = unit.endMs
        }
        return true
    }

    /**
     * Keep completed words white until the last trusted native word of this
     * lyric occurrence. Otherwise every short rest resets the entire Hangul
     * row to grey, even while Apple keeps the completed Japanese words lit.
     * Unlike closestVariant(), this never selects an unrelated past/future
     * chorus: only the measured first-to-last word window can own the paint.
     */
    fun activeVariant(
        positionMs: Long,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Int? = uniqueVariant(variants) { timed ->
        if (!orderedNativeClocks(timed)) return@uniqueVariant false
        positionMs >= timed.first().beginMs && positionMs < timed.last().endMs
    }

    /** Two identical choruses have the same text but different native timecodes. */
    fun closestVariant(
        positionMs: Long,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Int? = variants.indices.filter { variants[it].isNotEmpty() }.minByOrNull { index ->
        val units = variants[index]
        val first = units.minOf { it.beginMs }
        val last = units.maxOf { it.endMs }
        when {
            positionMs < first -> first - positionMs
            positionMs > last -> positionMs - last
            else -> 0L
        }
    }
}
