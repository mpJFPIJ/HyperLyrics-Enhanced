package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.root.aitrans.JapaneseToHangul
import com.juren233.hyperlyricsenhanced.root.aitrans.VerifiedJapaneseFragmentReading

/** Measured Android Layout offsets are UTF-16; letter identity is Unicode
 * code points. Never discard supplementary Han or bisect a surrogate pair. */
internal fun appleIsJapaneseVoicingMark(codePoint: Int): Boolean =
    JapaneseToHangul.isJapaneseVoicingMark(codePoint)

/** The same content units must drive native source identity, measured row
 * coverage, and Apple word-clock offsets. A decomposed Kana voicing mark is
 * phonetic content, not ignorable punctuation or a separate timed syllable. */
internal fun appleIsLyricSourceCodePoint(codePoint: Int): Boolean =
    JapaneseToHangul.isLyricSourceContent(codePoint)

internal fun appleHasSpokenSourceBase(text: String): Boolean =
    JapaneseToHangul.containsSpokenSourceBase(text)

private fun isSourceCodePointBoundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && !(offset > 0 && offset < text.length &&
        Character.isHighSurrogate(text[offset - 1]) &&
        Character.isLowSurrogate(text[offset])) &&
        // A raw token for か|゙ cannot prove the separately voiced sound of か.
        (offset == text.length || !appleIsJapaneseVoicingMark(
            Character.codePointAt(text, offset),
        ))

/** Shared provider→visual proof: supplementary letters cannot disappear as
 * two non-letter UTF-16 surrogate halves during Hangul normalization, and
 * Japanese voicing marks are source content, never Korean sound notation. */
internal fun appleOnlyHangulSoundLetters(text: String): Boolean =
    JapaneseToHangul.onlyHangulSoundLetters(text)

private fun sourceLetterOffsets(text: String): List<Int> = buildList {
    var offset = 0
    while (offset < text.length) {
        val codePoint = Character.codePointAt(text, offset)
        if (appleIsLyricSourceCodePoint(codePoint)) add(offset)
        offset += Character.charCount(codePoint)
    }
}

internal fun appleLyricSourceKey(text: String): String = buildString {
    for (offset in sourceLetterOffsets(text)) {
        val codePoint = Character.codePointAt(text, offset)
        appendCodePoint(codePoint)
    }
}

private fun sourceCodePointEnd(text: String, offset: Int): Int =
    offset + Character.charCount(Character.codePointAt(text, offset))

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

/** The host's measured Flexbox *child* rows are distinct from TextView.Layout
 * lines inside each child. The 6.5.3 karaoke-word XML defaults to singleLine,
 * but runtime views must still be measured before using either interpretation. */
internal object AppleMeasuredFlexRowPolicy {
    fun rows(bounds: List<Pair<Int, Int>>): IntArray? {
        if (bounds.isEmpty() || bounds.any { (top, bottom) -> bottom <= top }) return null
        val result = IntArray(bounds.size)
        var row = 0
        var rowBottom = bounds.first().second
        var sharedRowTop = bounds.first().first
        var sharedRowBottom = bounds.first().second
        var maxEarlierTop = bounds.first().first
        var previousRowBottom: Int? = null
        for (index in 1 until bounds.size) {
            val (top, bottom) = bounds[index]
            // Native child order supplies source-letter order. A later cell
            // entirely ABOVE an earlier cell cannot be projected to a
            // monotonically increasing physical row, even if some other
            // children still overlap. Refuse rather than misplace Hangul.
            if (bottom <= maxEarlierTop) return null
            maxEarlierTop = maxOf(maxEarlierTop, top)
            if (top >= rowBottom) {
                previousRowBottom = rowBottom
                row++
                rowBottom = bottom
                sharedRowTop = top
                sharedRowBottom = bottom
            } else {
                // Once a lower native row has started, a later sibling may
                // overlap its peers but cannot also cross into the earlier
                // row's ink box. Such geometry has no unique row ownership.
                if (previousRowBottom != null && top < previousRowBottom) return null
                // Overlap is not transitive. Three words may form a chain
                // A∩B, B∩C without sharing any physical y-coordinate;
                // their one-row Hangul placement is then unproven.
                if (top >= sharedRowBottom || bottom <= sharedRowTop) return null
                sharedRowTop = maxOf(sharedRowTop, top)
                sharedRowBottom = minOf(sharedRowBottom, bottom)
                rowBottom = maxOf(rowBottom, bottom)
            }
            result[index] = row
        }
        return result
    }

    fun count(bounds: List<Pair<Int, Int>>): Int? = rows(bounds)?.last()?.plus(1)
}

/** Shape only: no lyric text, inferred reading boundaries or native timecodes.
 * The TextView counts describe internal Layout lines; top/bottom describe
 * measured native Flexbox children. These are separate coordinate systems. */
internal data class AppleNativeWrapWordLineShape(
    val layoutLineCount: Int,
    val hardBreakLines: Int,
) {
    /** A literal newline ends a Layout line too, but is not a soft wrap. */
    val additionalSoftLines: Int get() = layoutLineCount - 1 - hardBreakLines
}

internal data class AppleNativeWrapCellShape(
    val top: Int,
    val bottom: Int,
    val nativeWordLines: List<AppleNativeWrapWordLineShape>,
)

internal data class AppleNativeMultiWrapEvidence(
    val sourceCells: Int,
    val sourceWords: Int,
    val flexRows: Int,
    val nativeWrappedCells: Int,
    val wrappedSourceChildren: Int,
    val wrappedWithPeers: Int,
    val wrappedMultiWordChildren: Int,
    val additionalNativeSoftLines: Int,
    val nativeHardBreakLines: Int,
)

/** Diagnose the geometry we cannot currently interleave, without converting
 * one TextView's internal soft lines into fabricated Flexbox child rows.
 * A wrapped source child sharing its flex row with another child is a distinct
 * paint/spacing problem from a solitary wrapped source child. */
internal object AppleNativeMultiWrapGeometryPolicy {
    fun isHardBreakAtLineEnd(text: CharSequence, endExclusive: Int): Boolean =
        endExclusive in 1..text.length && when (text[endExclusive - 1]) {
            '\n', '\u2028', '\u2029' -> true
            else -> false
        }

    fun shouldTrace(
        sourceCells: Int,
        hasMultilineNativeWord: Boolean,
        geometryPending: Boolean,
        interleavedRowsReady: Boolean,
        inlineSoftWrapReady: Boolean,
    ): Boolean = !geometryPending && sourceCells > 1 && hasMultilineNativeWord &&
        !interleavedRowsReady && !inlineSoftWrapReady

    fun summarize(cells: List<AppleNativeWrapCellShape>): AppleNativeMultiWrapEvidence? {
        if (cells.size < 2 || cells.any { cell ->
                cell.nativeWordLines.isEmpty() || cell.nativeWordLines.any { word ->
                    word.layoutLineCount <= 0 || word.hardBreakLines < 0 ||
                        word.additionalSoftLines < 0
                }
            }
        ) return null
        // The same row classifier now rejects reversed disjoint native
        // geometry for BOTH placement and content-free diagnostics.
        val rows = AppleMeasuredFlexRowPolicy.rows(cells.map { it.top to it.bottom })
            ?: return null
        val wrapped = cells.indices.filter { index ->
            cells[index].nativeWordLines.any { it.additionalSoftLines > 0 }
        }
        // A native hard newline also creates a physical Layout line. Report
        // a hard-only multi-line case with zero soft wraps so the final device
        // trace does not mistake an explicit break for a missing observation.
        if (wrapped.isEmpty() && cells.none { cell ->
                cell.nativeWordLines.any { it.hardBreakLines > 0 }
            }
        ) return null
        val peersByRow = rows.toList().groupingBy { it }.eachCount()
        return AppleNativeMultiWrapEvidence(
            sourceCells = cells.size,
            sourceWords = cells.sumOf { it.nativeWordLines.size },
            flexRows = rows.last() + 1,
            nativeWrappedCells = cells.sumOf { cell ->
                cell.nativeWordLines.count { it.layoutLineCount > 1 }
            },
            wrappedSourceChildren = wrapped.size,
            wrappedWithPeers = wrapped.count { (peersByRow[rows[it]] ?: 0) > 1 },
            wrappedMultiWordChildren = wrapped.count {
                cells[it].nativeWordLines.size > 1
            },
            additionalNativeSoftLines = cells.sumOf { cell ->
                cell.nativeWordLines.sumOf { it.additionalSoftLines }
            },
            nativeHardBreakLines = cells.sumOf { cell ->
                cell.nativeWordLines.sumOf { it.hardBreakLines }
            },
        )
    }
}

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
        if (!appleOnlyHangulSoundLetters(pronunciation)) return false
        val sourceLetters = sourceLetterOffsets(sourceText)
        if (sourceLetters.isEmpty() || sourceLetters.any { offset ->
                !JapaneseToHangul.isLiteralKanaCodePoint(
                    Character.codePointAt(sourceText, offset),
                )
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
            appleLyricSourceKey(nativeWords.joinToString("")) !=
                appleLyricSourceKey(sourceText)
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

    /** A partial map is not a complete phonetic proof. The only exception
     * is one Han-containing sound gap isolated by exact anchors; its actual
     * row ownership is still checked against measured geometry in plan().
     * Partial maps must not suppress independent full-kana transcription. */
    fun hasPotentialRowProjection(
        sourceText: String,
        pronunciation: String,
        spans: List<ApplePronunciationSourceToken>,
    ): Boolean = canDeriveKanaRows(sourceText, pronunciation) ||
        completeSourceReadingSpans(sourceText, pronunciation, spans) ||
        recoverSingleHanGap(sourceText, pronunciation, spans) != null

    private fun completeSourceReadingSpans(
        sourceText: String,
        pronunciation: String,
        spans: List<ApplePronunciationSourceToken>,
    ): Boolean {
        if (spans.isEmpty() || !RomanizationPolicy.isHangulPronunciation(pronunciation) ||
            !appleOnlyHangulSoundLetters(pronunciation))
            return false
        val letters = sourceLetterOffsets(sourceText)
        if (letters.isEmpty()) return false
        val covered = BooleanArray(sourceText.length)
        val sounds = StringBuilder()
        var previousEnd = 0
        for (span in spans) {
            if (span.sourceStart < previousEnd || span.sourceEnd <= span.sourceStart ||
                span.sourceEnd > sourceText.length ||
                !isSourceCodePointBoundary(sourceText, span.sourceStart) ||
                !isSourceCodePointBoundary(sourceText, span.sourceEnd) ||
                // Offsets and matching Hangul alone cannot prove that this
                // dictionary sound belongs to the current Japanese source.
                // The actual producer supplies the raw text for every span.
                span.sourceText == null || sourceText.substring(
                    span.sourceStart, span.sourceEnd,
                ) != span.sourceText ||
                !appleOnlyHangulSoundLetters(span.hangul) ||
                !appleHasSpokenSourceBase(span.sourceText) ||
                letters.none { it in span.sourceStart until span.sourceEnd }
            ) return false
            val sound = span.hangul.filter(Char::isLetterOrDigit)
            if (sound.isEmpty()) return false
            sounds.append(sound)
            for (offset in span.sourceStart until span.sourceEnd) covered[offset] = true
            previousEnd = span.sourceEnd
        }
        var offset = 0
        while (offset < sourceText.length) {
            val codePoint = Character.codePointAt(sourceText, offset)
            if (appleIsJapaneseVoicingMark(codePoint) && !covered[offset]) return false
            offset += Character.charCount(codePoint)
        }
        return letters.all { covered[it] } &&
            sounds.toString() == pronunciation.filter(Char::isLetterOrDigit)
    }

    /** Partial provider/dictionary anchors can locate the *one* otherwise
     * unreadable Kanji gap by subtraction from the selected complete Hangul
     * line. This is a visual-row proof only: the gap never gains fabricated
     * per-word clocks, and it must occupy just one measured source row.
     *
     * More than one missing source-letter interval, repeated sound anchors,
     * an independently readable kana mismatch or an internal row crossing
     * all remain unsplittable. The original anchors must name raw source
     * substrings, not just offsets that happen to contain similar glyphs. */
    private fun recoverSingleHanGap(
        sourceText: String,
        pronunciation: String,
        spans: List<ApplePronunciationSourceToken>,
        sourceRow: IntArray? = null,
    ): List<ApplePronunciationSourceToken>? {
        if (spans.isEmpty() || sourceText.isBlank() || pronunciation.isBlank() ||
            !RomanizationPolicy.isHangulPronunciation(pronunciation) ||
            !appleOnlyHangulSoundLetters(pronunciation)
        ) return null
        val fullSound = pronunciation.filter(Char::isLetterOrDigit)
        if (fullSound.isEmpty()) return null
        val sourceLetters = sourceLetterOffsets(sourceText)

        var sourceCursor = 0
        var soundCursor = 0
        var recoveredGap = false
        val completed = ArrayList<ApplePronunciationSourceToken>(spans.size + 1)

        fun appendGap(sourceEnd: Int, soundEnd: Int): Boolean {
            if (sourceEnd < sourceCursor || soundEnd < soundCursor) return false
            val offsets = sourceLetters.filter {
                it in sourceCursor until sourceEnd
            }
            val sound = fullSound.substring(soundCursor, soundEnd)
            if (offsets.isEmpty()) return sound.isEmpty()
            if (recoveredGap || sound.isEmpty()) return false
            val fragment = sourceText.substring(sourceCursor, sourceEnd)
            var hasHan = false
            // The sole inferred visual row may contain Kana around Kanji,
            // but its independently readable Kana must still reproduce its
            // exact part of the residual. Otherwise even a one-row gap could
            // erase し or misread the particle は without any token evidence.
            val pieces = mutableListOf<String>()
            val piece = StringBuilder()
            var previousKana: Boolean? = null
            var offset = 0
            while (offset < fragment.length) {
                val codePoint = Character.codePointAt(fragment, offset)
                val isHan = Character.UnicodeScript.of(codePoint) ==
                    Character.UnicodeScript.HAN || codePoint == '々'.code
                val isKana = JapaneseToHangul.isLiteralKanaCodePoint(codePoint)
                if (!isHan && !isKana && Character.isLetterOrDigit(codePoint)) return false
                if (isHan) hasHan = true
                if (isHan || isKana) {
                    if (previousKana != null && previousKana != isKana) {
                        pieces += piece.toString()
                        piece.setLength(0)
                    }
                    piece.appendCodePoint(codePoint)
                    previousKana = isKana
                } else {
                    // Do not combine Kana across native source separators:
                    // こ う must not be mistaken for the long vowel こう.
                    if (piece.isNotEmpty()) {
                        pieces += piece.toString()
                        piece.setLength(0)
                    }
                    previousKana = null
                }
                offset += Character.charCount(codePoint)
            }
            if (piece.isNotEmpty()) pieces += piece.toString()
            if (!hasHan || VerifiedJapaneseFragmentReading.split(pieces, sound) == null)
                return false
            if (sourceRow != null) {
                var gapRow = -1
                for (index in offsets) {
                    val row = sourceRow[index]
                    if (row < 0 || gapRow >= 0 && row != gapRow) return false
                    gapRow = row
                }
            }
            completed += ApplePronunciationSourceToken(sourceCursor, sourceEnd, sound, fragment)
            recoveredGap = true
            return true
        }

        for (span in spans) {
            if (span.sourceStart < sourceCursor ||
                span.sourceEnd <= span.sourceStart || span.sourceEnd > sourceText.length ||
                !isSourceCodePointBoundary(sourceText, span.sourceStart) ||
                !isSourceCodePointBoundary(sourceText, span.sourceEnd) ||
                span.sourceText == null || sourceText.substring(
                    span.sourceStart, span.sourceEnd,
                ) != span.sourceText ||
                !appleHasSpokenSourceBase(span.sourceText) ||
                !appleOnlyHangulSoundLetters(span.hangul)
            ) return null
            val anchorSound = span.hangul.filter(Char::isLetterOrDigit)
            if (anchorSound.isEmpty()) return null
            val anchorStart = fullSound.indexOf(anchorSound, soundCursor)
            if (anchorStart < 0 ||
                (span.sourceStart == sourceCursor && anchorStart != soundCursor) ||
                (span.sourceStart > sourceCursor && anchorStart > soundCursor &&
                    fullSound.indexOf(anchorSound, anchorStart + 1) >= 0)
            ) return null
            if (!appendGap(span.sourceStart, anchorStart)) return null
            completed += span
            sourceCursor = span.sourceEnd
            soundCursor = anchorStart + anchorSound.length
        }
        if (!appendGap(sourceText.length, fullSound.length) || !recoveredGap) return null
        return completed.takeIf {
            completeSourceReadingSpans(sourceText, pronunciation, it)
        }
    }

    fun plan(
        sourceText: String,
        pronunciation: String,
        cells: List<AppleVisualSourceCell>,
        spans: List<ApplePronunciationSourceToken>,
    ): List<AppleInterleavedReadingRow>? {
        if (sourceText.isBlank() || pronunciation.isBlank() || cells.isEmpty() ||
            cells.any { it.bottom <= it.top || it.nativeLineCount < 1 } ||
            !RomanizationPolicy.isHangulPronunciation(pronunciation) ||
            !appleOnlyHangulSoundLetters(pronunciation)
        ) return null

        val sourceLetters = sourceLetterOffsets(sourceText)
        val readingLetters = sourceLetterOffsets(pronunciation)
        if (sourceLetters.isEmpty() || readingLetters.isEmpty()) return null
        if (cells.joinToString("") { appleLyricSourceKey(it.text) } !=
            appleLyricSourceKey(sourceText)
        ) return null

        // One measured row classifier serves both visible placement and the
        // fallback geometry trace. It does not infer internal TextView wraps.
        val visualRows = AppleMeasuredFlexRowPolicy.rows(cells.map { it.top to it.bottom })
            ?: return null
        val rowNumber = visualRows.last()
        if (rowNumber == 0) return null
        // A *sole terminal* internally wrapped cell cannot be split between
        // its TextView.Layout lines, but that does not invalidate already
        // measured, complete Flexbox rows above it. Place that cell's WHOLE
        // verified reading only after the cell; never insert into its lines.
        // Any earlier wrapped cell or terminal-row peer is still unsupported.
        // Punctuation-only cells cannot establish an earlier/final sound row.
        val firstWrapped = cells.indexOfFirst { it.nativeLineCount > 1 }
        if (firstWrapped >= 0 && (firstWrapped != cells.lastIndex ||
                visualRows[cells.lastIndex - 1] == rowNumber ||
                sourceLetterOffsets(cells.last().text).isEmpty() ||
                cells.dropLast(1).none { sourceLetterOffsets(it.text).isNotEmpty() })
        ) return null

        // Map source UTF-16 character offsets to the corresponding measured
        // source row. Punctuation/whitespace may be absent from native cells.
        val sourceRow = IntArray(sourceText.length) { -1 }
        var consumed = 0
        for (index in cells.indices) {
            val letterCount = sourceLetterOffsets(cells[index].text).size
            if (consumed + letterCount > sourceLetters.size) return null
            for (offset in consumed until consumed + letterCount) {
                sourceRow[sourceLetters[offset]] = visualRows[index]
            }
            consumed += letterCount
        }
        if (consumed != sourceLetters.size) return null

        val sourceSpans = if (completeSourceReadingSpans(sourceText, pronunciation, spans)) {
            spans
        } else if (!canDeriveKanaRows(sourceText, pronunciation)) {
            // A unique residual Kanji fragment may own its whole sound only
            // when the *measured* native Flexbox keeps it on a single row.
            recoverSingleHanGap(sourceText, pronunciation, spans, sourceRow) ?: return null
        } else {
            // A run of kana belonging to a measured visual row is an exact
            // phonetic unit only if each independent conversion still joins
            // into the original pronunciation (digraph/final sound may not).
            val derived = (0..rowNumber).mapNotNull { row ->
                val offsets = sourceLetters.filter { sourceRow[it] == row }
                if (offsets.isEmpty()) return@mapNotNull null
                val start = offsets.first()
                val end = sourceCodePointEnd(sourceText, offsets.last())
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
                !isSourceCodePointBoundary(sourceText, span.sourceStart) ||
                !isSourceCodePointBoundary(sourceText, span.sourceEnd) ||
                !appleOnlyHangulSoundLetters(span.hangul) ||
                span.sourceText == null || sourceText.substring(
                    span.sourceStart, span.sourceEnd,
                ) != span.sourceText ||
                !appleHasSpokenSourceBase(span.sourceText)
            ) return null
            val segmentSourceLetters = sourceLetters.filter {
                it >= span.sourceStart && it < span.sourceEnd
            }
            if (segmentSourceLetters.isEmpty()) return null
            val sound = span.hangul.filter(Char::isLetterOrDigit)
            if (sound.isEmpty() || readingCursor + sound.length > readingLetters.size ||
                readingLetters.subList(readingCursor, readingCursor + sound.length)
                    .joinToString("") { index ->
                        pronunciation.substring(index, sourceCodePointEnd(pronunciation, index))
                    } != sound
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
                    sourceCodePointEnd(
                        pronunciation, readingLetters[localReading + fragmentSound.length - 1],
                    )
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
        source.substring(offsets.first(), sourceCodePointEnd(source, offsets.last()))
    }, fullSound)

    /** Exact text fragments may come from measured visual rows *or* Apple's
     * independently timed native word cells. Keep one shared phonetic proof
     * so row placement and native karaoke use identical guarded boundaries. */
    internal fun verifiedFragmentSounds(
        surfaces: List<String>,
        fullSound: String,
    ): List<String>? = VerifiedJapaneseFragmentReading.split(surfaces, fullSound)
}

/** Project a single native TextView's *measured* internal soft lines into the
 * same source↔reading rows used by flexbox wraps. A partial source map may
 * recover only one Han gap contained wholly within ONE actual Layout line;
 * the soft-wrap painter still requires every Layout line to have its own
 * row before reserving native line spacing. The caller must
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
                range.last >= displayedText.length ||
                !isSourceCodePointBoundary(displayedText, range.first) ||
                !isSourceCodePointBoundary(displayedText, range.last + 1)
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
