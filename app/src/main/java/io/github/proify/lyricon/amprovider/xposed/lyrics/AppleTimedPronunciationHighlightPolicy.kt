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
        val located = timedWords.mapNotNull { word ->
            if (word.beginMs < 0L || word.endMs <= word.beginMs) return@mapNotNull null
            val normalizedWord = word.text.filter(Char::isLetterOrDigit)
            if (normalizedWord.isEmpty()) return@mapNotNull null
            val offset = normalizedSource.indexOf(normalizedWord, sourceCursor)
            if (offset < 0) return@mapNotNull null
            sourceCursor = offset + normalizedWord.length
            LocatedWord(
                sourceStart = sourceChars[offset],
                sourceEnd = sourceChars[sourceCursor - 1] + 1,
                beginMs = word.beginMs,
                endMs = word.endMs,
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

        // Validate a *complete* reading before taking its exact source offsets.
        // A partial/AI-invented token must not silently misalign the real lyrics.
        val acceptedSpans = phoneticSpans.takeIf { spans ->
            spans.isNotEmpty() && spans.zipWithNext().all { (a, b) ->
                a.sourceEnd <= b.sourceStart
            } && spans.all { span ->
                span.sourceStart >= 0 && span.sourceEnd > span.sourceStart &&
                    span.sourceEnd <= sourceText.length &&
                    (span.sourceText == null ||
                        sourceText.substring(span.sourceStart, span.sourceEnd) == span.sourceText)
            } && spans.flatMap { it.hangul.filter(Char::isLetterOrDigit).toList() } ==
                pronouncedOffsets.map { pronunciation[it] }
        }

        val coordinates = if (acceptedSpans != null) {
            buildList {
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
        } else {
            // If Apple's timing is partial, estimating the whole reading
            // from the remaining words would light missing syllables too early.
            if (!fullNativeCoverage && !hasLineWindow) return emptyList()
            // Manual/unsupported text has no verified phonetic token map.
            // Use Apple word times, distributing syllables across the source;
            // this approximation is still progressive rather than a whole-line flash.
            val sourceStart = 0.0
            val sourceEnd = sourceText.length.toDouble()
            val width = sourceEnd - sourceStart
            List(pronouncedOffsets.size) { index ->
                (sourceStart + width * index / pronouncedOffsets.size) to
                    (sourceStart + width * (index + 1) / pronouncedOffsets.size)
            }
        }

        return pronouncedOffsets.zip(coordinates).mapNotNull { (textIndex, coordinate) ->
            val begin = clockAt(coordinate.first, ending = false) ?: return@mapNotNull null
            val end = clockAt(coordinate.second, ending = true) ?: return@mapNotNull null
            if (end <= begin) return@mapNotNull null
            AppleTimedReadingUnit(textIndex, textIndex + 1, begin, end)
        }
    }

    fun progress(positionMs: Long, unit: AppleTimedReadingUnit): Float = when {
        positionMs <= unit.beginMs -> 0f
        positionMs >= unit.endMs -> 1f
        else -> (positionMs - unit.beginMs).toFloat() /
            (unit.endMs - unit.beginMs).toFloat()
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
