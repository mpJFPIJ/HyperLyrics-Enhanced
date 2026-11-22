package io.github.proify.lyricon.amprovider.xposed

/** The actual laid-out native cell bounds, before inserting any Hangul child. */
internal data class AppleVisualSourceCell(
    val text: String,
    val top: Int,
    val bottom: Int,
)

/** A complete phonetic-token group belonging immediately below one Japanese visual row. */
internal data class AppleInterleavedReadingRow(
    val insertAfterSourceCell: Int,
    val text: String,
    val readingStart: Int,
    val readingEnd: Int,
)

/**
 * Detect real flexbox wraps, then assign WHOLE verified pronunciation tokens
 * to the visual row containing the token's first Japanese source character.
 * This never estimates a Hangul syllable boundary from Japanese character counts.
 * If a row cannot be accounted for, leave the established single-block path intact.
 */
internal object AppleInterleavedReadingPolicy {
    fun plan(
        sourceText: String,
        pronunciation: String,
        cells: List<AppleVisualSourceCell>,
        spans: List<ApplePronunciationSourceToken>,
    ): List<AppleInterleavedReadingRow>? {
        if (sourceText.isBlank() || pronunciation.isBlank() || cells.isEmpty() ||
            spans.isEmpty() || cells.any { it.bottom <= it.top }
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

        val covered = BooleanArray(sourceText.length)
        val assigned = Array(rowNumber + 1) { mutableListOf<Pair<Int, Int>>() }
        var previousSourceEnd = 0
        var previousRow = 0
        var readingCursor = 0
        for (span in spans) {
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
            val row = sourceRow[segmentSourceLetters.first()]
            if (row < previousRow || row !in assigned.indices) return null
            assigned[row] += readingLetters[readingCursor] to
                (readingLetters[readingCursor + sound.length - 1] + 1)
            readingCursor += sound.length
            previousRow = row
            previousSourceEnd = span.sourceEnd
            for (offset in segmentSourceLetters) covered[offset] = true
        }
        if (readingCursor != readingLetters.size || sourceLetters.any { !covered[it] } ||
            assigned.any { it.isEmpty() }
        ) return null

        val result = mutableListOf<AppleInterleavedReadingRow>()
        for (row in assigned.indices) {
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
}
