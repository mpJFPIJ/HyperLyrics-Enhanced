package io.github.proify.lyricon.amprovider.xposed

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
    // Original Apple source word index; -1 when only line-level time exists.
    val sourceWordIndex: Int = -1,
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

    fun units(
        sourceText: String,
        pronunciation: String,
        timedWords: List<AppleTimedSourceWord>,
        phoneticSpans: List<ApplePronunciationSourceToken> = emptyList(),
        lineBeginMs: Long? = null,
        lineEndMs: Long? = null,
    ): List<AppleTimedReadingUnit> {
        if (sourceText.isBlank() || pronunciation.isBlank()) return emptyList()
        val sourceChars = sourceText.indices.filter { sourceText[it].isLetterOrDigit() }
        val normalizedSource = sourceChars.joinToString("") { sourceText[it].toString() }
        var sourceCursor = 0
        val located = timedWords.mapIndexedNotNull { nativeIndex, word ->
            if (word.beginMs < 0L || word.endMs <= word.beginMs) return@mapIndexedNotNull null
            val normalizedWord = word.text.filter(Char::isLetterOrDigit)
            if (normalizedWord.isEmpty()) return@mapIndexedNotNull null
            val offset = normalizedSource.indexOf(normalizedWord, sourceCursor)
            if (offset < 0) return@mapIndexedNotNull null
            sourceCursor = offset + normalizedWord.length
            LocatedWord(
                sourceStart = sourceChars[offset],
                sourceEnd = sourceChars[sourceCursor - 1] + 1,
                beginMs = word.beginMs,
                endMs = word.endMs,
                nativeIndex = nativeIndex,
            )
        }
        val hasLineWindow = lineBeginMs != null && lineEndMs != null &&
            lineBeginMs >= 0L && lineEndMs > lineBeginMs
        if (located.isEmpty() && !hasLineWindow) return emptyList()
        val coveredSourcePositions = BooleanArray(sourceText.length)
        located.forEach { word ->
            for (index in word.sourceStart until word.sourceEnd) {
                coveredSourcePositions[index] = true
            }
        }
        val fullNativeCoverage = sourceChars.isNotEmpty() &&
            sourceChars.all { coveredSourcePositions[it] }

        val pronouncedOffsets = pronunciation.indices.filter {
            pronunciation[it].isLetterOrDigit()
        }
        if (pronouncedOffsets.isEmpty()) return emptyList()

        // A tokenizer boundary establishes what a complete Korean expression
        // means; it does NOT establish when each of its syllables is spoken.
        // Accept only structural source spans whose sound is actually present
        // in the generated/loaded reading, preserving source UTF-16 offsets.
        val structurallyValidSpans = phoneticSpans.takeIf { spans ->
            spans.isNotEmpty() && spans.zipWithNext().all { (a, b) ->
                a.sourceEnd <= b.sourceStart
            } && spans.all { span ->
                span.sourceStart >= 0 && span.sourceEnd > span.sourceStart &&
                    span.sourceEnd <= sourceText.length &&
                    span.hangul.any(Char::isLetterOrDigit) &&
                    (span.sourceText == null ||
                        sourceText.substring(span.sourceStart, span.sourceEnd) == span.sourceText)
            }
        }
        val pronunciationLetters = pronouncedOffsets.joinToString("") {
            pronunciation[it].toString()
        }
        val completeSpans = structurallyValidSpans != null &&
            structurallyValidSpans.joinToString("") {
                it.hangul.filter(Char::isLetterOrDigit)
            } == pronunciationLetters

        if (structurallyValidSpans != null) {
            var cursor = 0
            val verified = mutableListOf<AppleTimedReadingUnit>()
            var ambiguous = false
            for (span in structurallyValidSpans) {
                val phonetic = span.hangul.filter(Char::isLetterOrDigit)
                val index = if (completeSpans) cursor else
                    pronunciationLetters.indexOf(phonetic, cursor)
                if (index < 0 || !pronunciationLetters.startsWith(phonetic, index) ||
                    (!completeSpans && pronunciationLetters.indexOf(phonetic, index + 1) >= 0)
                ) {
                    ambiguous = true
                    break
                }
                cursor = index + phonetic.length

                val segmentSource = sourceChars.filter {
                    it in span.sourceStart until span.sourceEnd
                }
                if (segmentSource.isEmpty() ||
                    !segmentSource.all { coveredSourcePositions[it] }
                ) continue
                val native = located.filter {
                    it.sourceEnd > span.sourceStart && it.sourceStart < span.sourceEnd
                }
                // A token must consume entire native cells. If only part of a
                // cell is known, its timing cannot be split safely.
                if (native.isEmpty() || native.first().sourceStart < span.sourceStart ||
                    native.last().sourceEnd > span.sourceEnd
                ) continue
                val begin = native.first().beginMs
                val end = native.last().endMs
                if (end <= begin) continue
                verified += AppleTimedReadingUnit(
                    start = pronouncedOffsets[index],
                    end = pronouncedOffsets[cursor - 1] + 1,
                    beginMs = begin,
                    endMs = end,
                    sourceWordIndex = native.singleOrNull()?.nativeIndex ?: -1,
                )
            }
            if (!ambiguous && verified.isNotEmpty()) return verified
        }

        // No trustworthy phonetic-to-word anchors: the only defensible
        // fallback is one WHOLE line interval. Never invent fine timing.
        val begin = if (hasLineWindow) lineBeginMs!! else
            located.minOfOrNull(LocatedWord::beginMs) ?: return emptyList()
        val end = if (hasLineWindow) lineEndMs!! else
            located.maxOfOrNull(LocatedWord::endMs) ?: return emptyList()
        if ((!fullNativeCoverage && !hasLineWindow) || end <= begin) return emptyList()
        return listOf(AppleTimedReadingUnit(
            start = pronouncedOffsets.first(),
            end = pronouncedOffsets.last() + 1,
            beginMs = begin,
            endMs = end,
        ))
    }

    fun progress(positionMs: Long, unit: AppleTimedReadingUnit): Float = when {
        positionMs <= unit.beginMs -> 0f
        positionMs >= unit.endMs -> 1f
        else -> (positionMs - unit.beginMs).toFloat() /
            (unit.endMs - unit.beginMs).toFloat()
    }

    /**
     * A line must not be highlighted when no source lyric occurrence is
     * currently playing. The old closestVariant() returned a previous or
     * future chorus even HOURS outside its timestamps.
     */
    fun activeVariant(
        positionMs: Long,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Int? = variants.indices.firstOrNull { index ->
        val timed = variants[index]
        // A partial verified map can contain an unknown interval between two
        // real source words. It is not evidence of continuous singing.
        timed.any { positionMs >= it.beginMs && positionMs < it.endMs }
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
