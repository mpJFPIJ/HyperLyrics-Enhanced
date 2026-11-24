package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation
import com.juren233.hyperlyricsenhanced.root.aitrans.JapaneseToHangul

/** The actual laid-out native cell bounds, before inserting any Hangul child. */
internal data class AppleVisualSourceCell(
    val text: String,
    val top: Int,
    val bottom: Int,
    val nativeLineCount: Int = 1,
)

/** A complete phonetic-token group belonging immediately below one Japanese visual row. */
internal data class AppleInterleavedReadingRow(
    val insertAfterSourceCell: Int,
    val text: String,
    val readingStart: Int,
    val readingEnd: Int,
)

/**
 * Detect real flexbox wraps and map verified phonetic boundaries to their
 * corresponding Japanese rows. Crossing literal kana must independently
 * reproduce the full sound. One mixed Kanji/kana token can also split when
 * verified kana fragments pin down both sides of exactly one remaining
 * Kanji-containing row. Multiple unknown Kanji fragments and non-additive
 * kana sequences remain indivisible; we never guess offsets by length.
 */
internal object AppleInterleavedReadingPolicy {
    /** An absent AI token map can still be recovered for literal kana, but
     * only when the *entire* reading is reproduced by the trusted converter.
     * Mixed Kanji/English and altered particle readings require real spans. */
    fun canDeriveKanaRows(sourceText: String, pronunciation: String): Boolean {
        val sourceLetters = sourceText.filter(Char::isLetterOrDigit)
        if (sourceLetters.isEmpty() || sourceLetters.any {
                it !in '\u3040'..'\u30ff'
            }
        ) return false
        val expected = JapaneseToHangul.fromKana(sourceText)
            ?.filter(Char::isLetterOrDigit) ?: return false
        return expected.isNotEmpty() &&
            expected == pronunciation.filter(Char::isLetterOrDigit)
    }

    /** A native word clock can own a literal kana reading when transcribing
     * each complete word reproduces the selected pronunciation exactly. */
    fun verifiedKanaWords(
        sourceText: String, nativeWords: List<String>, pronunciation: String,
    ): List<LyricWordPronunciation>? {
        if (!canDeriveKanaRows(sourceText, pronunciation) ||
            nativeWords.joinToString("").filter(Char::isLetterOrDigit) !=
                sourceText.filter(Char::isLetterOrDigit)
        ) return null
        val words = nativeWords.map { word ->
            val reading = JapaneseToHangul.fromKana(word) ?: return null
            LyricWordPronunciation(word, reading)
        }
        return words.takeIf {
            words.joinToString("") { it.hangul }.filter(Char::isLetterOrDigit) ==
                pronunciation.filter(Char::isLetterOrDigit)
        }
    }

    fun plan(
        sourceText: String,
        pronunciation: String,
        cells: List<AppleVisualSourceCell>,
        spans: List<ApplePronunciationSourceToken>,
    ): List<AppleInterleavedReadingRow>? {
        if (sourceText.isBlank() || pronunciation.isBlank() || cells.isEmpty() ||
            cells.any { it.bottom <= it.top || it.nativeLineCount != 1 }
        ) return null

        val sourceLetters = sourceText.indices.filter { sourceText[it].isLetterOrDigit() }
        val readingLetters = pronunciation.indices.filter {
            pronunciation[it].isLetterOrDigit()
        }
        if (sourceLetters.isEmpty() || readingLetters.isEmpty()) return null
        if (cells.joinToString("") { it.text.filter(Char::isLetterOrDigit) } !=
            sourceLetters.joinToString("") { sourceText[it].toString() }
        ) return null

        // A new row starts only when the next measured native cell lies below
        // all preceding overlapping cells; small baseline/top differences do
        // not create fake wraps.
        val visualRows = IntArray(cells.size)
        var rowNumber = 0
        var rowBottom = cells.first().bottom
        for (index in 1 until cells.size) {
            val cell = cells[index]
            if (cell.top >= rowBottom) {
                rowNumber++
                rowBottom = cell.bottom
            } else {
                rowBottom = maxOf(rowBottom, cell.bottom)
            }
            visualRows[index] = rowNumber
        }
        if (rowNumber == 0) return null

        // Map source UTF-16 character offsets to the corresponding measured
        // source row. Punctuation/whitespace may be absent from native cells.
        val sourceRow = IntArray(sourceText.length) { -1 }
        var consumed = 0
        for (index in cells.indices) {
            val letterCount = cells[index].text.count(Char::isLetterOrDigit)
            if (consumed + letterCount > sourceLetters.size) return null
            for (offset in consumed until consumed + letterCount) {
                sourceRow[sourceLetters[offset]] = visualRows[index]
            }
            consumed += letterCount
        }
        if (consumed != sourceLetters.size) return null

        val sourceSpans = if (spans.isNotEmpty()) spans else {
            if (!canDeriveKanaRows(sourceText, pronunciation)) return null
            // A run of kana belonging to a measured visual row is an exact
            // phonetic unit only if each independent conversion still joins
            // into the original pronunciation (digraph/final sound may not).
            val derived = (0..rowNumber).mapNotNull { row ->
                val offsets = sourceLetters.filter { sourceRow[it] == row }
                if (offsets.isEmpty()) return@mapNotNull null
                val start = offsets.first()
                val end = offsets.last() + 1
                val fragment = sourceText.substring(start, end)
                val sound = JapaneseToHangul.fromKana(fragment) ?: return null
                ApplePronunciationSourceToken(start, end, sound, fragment)
            }
            if (derived.joinToString("") { it.hangul.filter(Char::isLetterOrDigit) } !=
                pronunciation.filter(Char::isLetterOrDigit)
            ) return null
            derived
        }

        val covered = BooleanArray(sourceText.length)
        val assigned = Array(rowNumber + 1) { mutableListOf<Pair<Int, Int>>() }
        var previousSourceEnd = 0
        var previousRow = 0
        var readingCursor = 0
        for (span in sourceSpans) {
            if (span.sourceStart < previousSourceEnd || span.sourceStart < 0 ||
                span.sourceEnd <= span.sourceStart || span.sourceEnd > sourceText.length ||
                (span.sourceText != null && sourceText.substring(
                    span.sourceStart, span.sourceEnd,
                ) != span.sourceText)
            ) return null
            val segmentSourceLetters = sourceLetters.filter {
                it >= span.sourceStart && it < span.sourceEnd
            }
            if (segmentSourceLetters.isEmpty()) return null
            val sound = span.hangul.filter(Char::isLetterOrDigit)
            if (sound.isEmpty() || readingCursor + sound.length > readingLetters.size ||
                readingLetters.subList(readingCursor, readingCursor + sound.length)
                    .joinToString("") { pronunciation[it].toString() } != sound
            ) return null
            val observedRows = segmentSourceLetters.map { sourceRow[it] }
            if (observedRows.zipWithNext().any { (left, right) -> left > right }) return null
            val fragments = segmentSourceLetters.groupBy { sourceRow[it] }
            if (fragments.keys.any { it !in assigned.indices } ||
                fragments.keys.toList() != fragments.keys.sorted()
            ) return null
            val resolvedSounds = if (fragments.size == 1) listOf(sound) else {
                resolveCrossRowSounds(sourceText, fragments.values.toList(), sound)
                    ?: return null
            }
            var localReading = readingCursor
            fragments.keys.zip(resolvedSounds).forEach { (row, fragmentSound) ->
                if (row < previousRow || localReading + fragmentSound.length > readingLetters.size)
                    return null
                assigned[row] += readingLetters[localReading] to
                    (readingLetters[localReading + fragmentSound.length - 1] + 1)
                localReading += fragmentSound.length
                previousRow = row
            }
            if (localReading != readingCursor + sound.length) return null
            readingCursor += sound.length
            previousSourceEnd = span.sourceEnd
            for (offset in segmentSourceLetters) covered[offset] = true
        }
        if (readingCursor != readingLetters.size || sourceLetters.any { !covered[it] } ||
            assigned.indices.any { row -> assigned[row].isEmpty() && row in sourceRow }
        ) return null

        val result = mutableListOf<AppleInterleavedReadingRow>()
        for (row in assigned.indices) {
            // A punctuation-only visual row has no spoken Hangul of its own.
            if (assigned[row].isEmpty()) continue
            val first = assigned[row].first().first
            val end = assigned[row].last().second
            val lastSourceCell = visualRows.indexOfLast { it == row }
            if (lastSourceCell < 0 || end <= first) return null
            result += AppleInterleavedReadingRow(
                insertAfterSourceCell = lastSourceCell,
                text = pronunciation.substring(first, end),
                readingStart = first,
                readingEnd = end,
            )
        }
        return result
    }

    /** A verified full-context phonetic token may cross a native wrap between
     * one Kanji-containing fragment and independently readable literal kana.
     * The known kana must reproduce an exact sound prefix/suffix. Only then
     * does the *one* remaining source row own the residual verified sound.
     * Neither two unverified Kanji rows nor a non-additive kana/digraph split
     * may be divided by character ratios or syllable timing guesses. */
    private fun resolveCrossRowSounds(
        source: String,
        fragments: List<List<Int>>,
        fullSound: String,
    ): List<String>? = verifiedFragmentSounds(fragments.map { offsets ->
        source.substring(offsets.first(), offsets.last() + 1)
    }, fullSound)

    /** Exact text fragments may come from measured visual rows *or* Apple's
     * independently timed native word cells. Keep one shared phonetic proof
     * so row placement and native karaoke use identical guarded boundaries. */
    internal fun verifiedFragmentSounds(
        surfaces: List<String>,
        fullSound: String,
    ): List<String>? {
        val literalSounds = surfaces.map { fragment ->
            if (fragment.any { it !in '\u3040'..'\u30ff' && it != 'ー' }) null
            else JapaneseToHangul.fromKana(fragment)?.filter(Char::isLetterOrDigit)
        }
        if (literalSounds.all { !it.isNullOrEmpty() }) {
            return literalSounds.filterNotNull().takeIf { it.joinToString("") == fullSound }
        }
        val unknown = literalSounds.indices.filter { literalSounds[it].isNullOrEmpty() }
        if (unknown.size != 1 || surfaces[unknown.single()].none {
                it in '\u3400'..'\u9fff' || it == '々'
            }
        ) return null
        val index = unknown.single()
        val knownPrefix = literalSounds.take(index).joinToString("") { it.orEmpty() }
        val knownSuffix = literalSounds.drop(index + 1).joinToString("") { it.orEmpty() }
        if (!fullSound.startsWith(knownPrefix) || !fullSound.endsWith(knownSuffix) ||
            fullSound.length <= knownPrefix.length + knownSuffix.length
        ) return null
        val residual = fullSound.substring(
            knownPrefix.length, fullSound.length - knownSuffix.length,
        )
        // A dangling compatibility jamo (e.g. ㄴ) must never become an
        // independently painted visual row; only complete Hangul syllables
        // are safe residual sounds for a Japanese Kanji fragment.
        if (residual.any { it !in '가'..'힣' }) return null
        return literalSounds.mapIndexed { fragment, sound ->
            if (fragment == index) residual else sound ?: return null
        }.takeIf { it.joinToString("") == fullSound }
    }
}

/** Project a single native TextView's *measured* internal soft lines into the
 * same verified source↔reading rows used by flexbox wraps. The caller must
 * supply TextView.Layout's UTF-16 lineStart/lineEnd offsets, never estimates
 * based on font width or Japanese character count. A changed visual script
 * cannot borrow source offsets from the original Japanese text. */
internal object AppleNativeSoftWrapReadingPolicy {
    fun plan(
        sourceText: String,
        displayedText: String,
        pronunciation: String,
        visualLineRanges: List<IntRange>,
        spans: List<ApplePronunciationSourceToken>,
    ): List<AppleInterleavedReadingRow>? {
        if (visualLineRanges.size < 2 || sourceText != displayedText) return null
        var nextStart = 0
        val lines = visualLineRanges.mapIndexed { index, range ->
            if (range.first != nextStart || range.isEmpty() ||
                range.last >= displayedText.length
            ) return null
            nextStart = range.last + 1
            AppleVisualSourceCell(
                text = displayedText.substring(range.first, nextStart),
                top = index * 2,
                bottom = index * 2 + 1,
            )
        }
        if (nextStart != displayedText.length) return null
        return AppleInterleavedReadingPolicy.plan(
            sourceText, pronunciation, lines, spans,
        )?.takeIf { rows ->
            rows.all { it.insertAfterSourceCell in lines.indices } &&
                rows.lastOrNull()?.insertAfterSourceCell == lines.lastIndex
        }
    }
}

/** Project verified windows only where the entire sound is visible. */
internal object AppleInterleavedReadingTimingPolicy {
    fun project(
        variants: List<List<AppleTimedReadingUnit>>,
        fullPronunciation: String,
        rows: List<AppleInterleavedReadingRow>,
        row: AppleInterleavedReadingRow,
        verifiedCrossRow: Boolean = false,
    ): List<List<AppleTimedReadingUnit>> {
        return variants.map { units ->
            units.mapNotNull { unit ->
                // A sentence-level clock cannot identify which visual row is
                // sung. Leave the split rows readable without fabricated clocks.
                if ((rows.size > 1 && unit.isWholeLine) ||
                    unit.start < 0 || unit.end > fullPronunciation.length || unit.start >= unit.end
                ) null else unit.copy(
                    start = unit.start - row.readingStart,
                    end = unit.end - row.readingStart,
                ).let { local ->
                    val crosses = unit.start < row.readingStart ||
                        unit.end > row.readingEnd
                    if (!crosses) local else {
                        // The original native word window is one indivisible
                        // clock. Only fully verified source↔reading visual rows
                        // can share its *same* interval: each row's TextPaint
                        // measures cumulative Hangul advances for that word.
                        val start = maxOf(unit.start, row.readingStart)
                        val end = minOf(unit.end, row.readingEnd)
                        if (!verifiedCrossRow || unit.isWholeLine || end <= start) null
                        else local.copy(
                            start = start - row.readingStart,
                            end = end - row.readingStart,
                            sweepFullReading = fullPronunciation.substring(unit.start, unit.end),
                            sweepStartOffset = start - unit.start,
                        )
                    }
                }
            }
        }
    }
}
