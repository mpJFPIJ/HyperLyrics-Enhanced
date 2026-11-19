package io.github.proify.lyricon.amprovider.xposed

import kotlin.math.roundToLong

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

/** One Hangul character's individual animation interval; no synthetic Apple word is created. */
internal data class AppleTimedReadingUnit(
    val start: Int,
    val end: Int,
    val beginMs: Long,
    val endMs: Long,
    // Original Apple source word index; -1 when only line-level time exists.
    val sourceWordIndex: Int = -1,
)

/**
 * Apple's real begin/duration are the only clock anchors. Where Apple supplies
 * only a multi-syllable word timestamp, syllable timing is an interpolation,
 * never represented as official per-syllable timing.
 */
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
        val fullNativeCoverage = sourceChars.all { coveredSourcePositions[it] }

        val pronouncedOffsets = pronunciation.indices.filter {
            pronunciation[it].isLetterOrDigit()
        }
        if (pronouncedOffsets.isEmpty()) return emptyList()

        fun clockAt(sourceOffset: Double, ending: Boolean): Long? {
            val word = located.firstOrNull { native ->
                if (ending) sourceOffset > native.sourceStart && sourceOffset <= native.sourceEnd
                else sourceOffset >= native.sourceStart && sourceOffset < native.sourceEnd
            }
            if (word != null) {
                val fraction = ((sourceOffset - word.sourceStart) /
                    (word.sourceEnd - word.sourceStart)).coerceIn(0.0, 1.0)
                return (word.beginMs + (word.endMs - word.beginMs) * fraction).roundToLong()
            }
            // Some Apple rows expose only some word timestamps. Preserve each
            // real timestamp; interpolate *only the uncovered interval* between
            // neighboring Apple words and/or the genuine native line boundaries.
            if (!hasLineWindow) return null
            val before = located.lastOrNull { it.sourceEnd <= sourceOffset }
            val after = located.firstOrNull { it.sourceStart >= sourceOffset }
            val sourceFrom = (before?.sourceEnd ?: 0).toDouble()
            val sourceTo = (after?.sourceStart ?: sourceText.length).toDouble()
            val timeFrom = before?.endMs ?: lineBeginMs!!
            val timeTo = after?.beginMs ?: lineEndMs!!
            if (sourceTo <= sourceFrom || timeTo <= timeFrom) return null
            val fraction = ((sourceOffset - sourceFrom) /
                (sourceTo - sourceFrom)).coerceIn(0.0, 1.0)
            return (timeFrom + (timeTo - timeFrom) * fraction).roundToLong()
        }

        // Preserve verified source-token anchors even when only part of the
        // reading was tokenized. Unknown syllables must never inherit an Apple
        // source-word index merely because a proportional fallback crossed it.
        val structurallyValidSpans = phoneticSpans.takeIf { spans ->
            spans.isNotEmpty() && spans.zipWithNext().all { (a, b) ->
                a.sourceEnd <= b.sourceStart
            } && spans.all { span ->
                span.sourceStart >= 0 && span.sourceEnd > span.sourceStart &&
                    span.sourceEnd <= sourceText.length &&
                    (span.sourceText == null ||
                        sourceText.substring(span.sourceStart, span.sourceEnd) == span.sourceText)
            }
        }
        val acceptedSpans = structurallyValidSpans.takeIf { spans ->
            spans != null &&
                spans.flatMap { it.hangul.filter(Char::isLetterOrDigit).toList() } ==
                pronouncedOffsets.map { pronunciation[it] }
        }
        val partialAnchors: List<Pair<Double, Double>?>? =
            if (acceptedSpans != null) null else structurallyValidSpans?.let { spans ->
                val pronunciationLetters =
                    pronouncedOffsets.joinToString("") { pronunciation[it].toString() }
                val anchors = MutableList<Pair<Double, Double>?>(pronouncedOffsets.size) { null }
                var nextPronunciationIndex = 0
                var valid = true
                for (span in spans) {
                    val phonetic = span.hangul.filter(Char::isLetterOrDigit)
                    val index = if (phonetic.isEmpty()) -1 else
                        pronunciationLetters.indexOf(phonetic, nextPronunciationIndex)
                    if (index < 0) {
                        valid = false
                        break
                    }
                    val width = (span.sourceEnd - span.sourceStart).toDouble()
                    for (unit in phonetic.indices) {
                        anchors[index + unit] =
                            (span.sourceStart + width * unit / phonetic.length) to
                                (span.sourceStart + width * (unit + 1) / phonetic.length)
                    }
                    nextPronunciationIndex = index + phonetic.length
                }
                anchors.takeIf { valid && it.any { anchor -> anchor != null } &&
                    it.any { anchor -> anchor == null } }
            }

        val coordinates: List<Pair<Double, Double>?> = when {
            acceptedSpans != null -> buildList {
                acceptedSpans.forEach { span ->
                    val count = span.hangul.count(Char::isLetterOrDigit)
                    if (count == 0) return@forEach
                    val width = (span.sourceEnd - span.sourceStart).toDouble()
                    repeat(count) { index ->
                        add(
                            (span.sourceStart + width * index / count) to
                                (span.sourceStart + width * (index + 1) / count),
                        )
                    }
                }
            }
            partialAnchors != null -> {
                val estimated = partialAnchors.toMutableList()
                // A real native line window allows a bounded approximation
                // for missing syllables. Without it they stay unanimated.
                if (hasLineWindow) {
                    var cursor = 0
                    while (cursor < estimated.size) {
                        if (estimated[cursor] != null) {
                            cursor++
                            continue
                        }
                        val gapStart = cursor
                        while (cursor < estimated.size && estimated[cursor] == null) cursor++
                        val gapEnd = cursor
                        val sourceFrom =
                            if (gapStart == 0) 0.0 else estimated[gapStart - 1]?.second ?: 0.0
                        val sourceTo =
                            if (gapEnd == estimated.size) sourceText.length.toDouble()
                            else estimated[gapEnd]?.first ?: sourceText.length.toDouble()
                        if (sourceTo <= sourceFrom) continue
                        val width = sourceTo - sourceFrom
                        val count = gapEnd - gapStart
                        for (offset in 0 until count) {
                            estimated[gapStart + offset] =
                                (sourceFrom + width * offset / count) to
                                    (sourceFrom + width * (offset + 1) / count)
                        }
                    }
                }
                estimated
            }
            else -> {
                // Without a validated map, partial native word coverage alone
                // cannot safely animate the entire reading.
                if (!fullNativeCoverage && !hasLineWindow) return emptyList()
                val width = sourceText.length.toDouble()
                List(pronouncedOffsets.size) { index ->
                    (width * index / pronouncedOffsets.size) to
                        (width * (index + 1) / pronouncedOffsets.size)
                }
            }
        }

        return pronouncedOffsets.mapIndexedNotNull { syllableIndex, textIndex ->
            val coordinate = coordinates.getOrNull(syllableIndex)
                ?: return@mapIndexedNotNull null
            val begin = clockAt(coordinate.first, ending = false)
                ?: return@mapIndexedNotNull null
            val end = clockAt(coordinate.second, ending = true)
                ?: return@mapIndexedNotNull null
            if (end <= begin) return@mapIndexedNotNull null
            val midpoint = (coordinate.first + coordinate.second) / 2.0
            val sourceWord =
                if (partialAnchors != null && partialAnchors[syllableIndex] == null) -1
                else located.firstOrNull { native ->
                    midpoint >= native.sourceStart && midpoint < native.sourceEnd
                }?.nativeIndex ?: -1
            AppleTimedReadingUnit(
                textIndex, textIndex + 1, begin, end, sourceWordIndex = sourceWord,
            )
        }
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
        timed.isNotEmpty() &&
            positionMs >= timed.minOf { it.beginMs } &&
            positionMs < timed.maxOf { it.endMs }
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
