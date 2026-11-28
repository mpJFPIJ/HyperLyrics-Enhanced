package com.juren233.hyperlyricsenhanced.root.aitrans

/** All offsets are UTF-16 positions in the untouched source lyric line. */
internal data class SourceReadingSpan(
    val sourceStart: Int,
    val sourceEnd: Int,
    val sourceText: String,
    val kana: String,
    val hangul: String,
)

internal enum class LineReadingStatus { COMPLETE, MISSING_UNSUPPORTED, EMPTY }

internal data class LineReadingResult(
    val text: String?,
    val spans: List<SourceReadingSpan>,
    val status: LineReadingStatus,
)

/** Immutable snapshot of one token from a *single* full-line Sudachi analysis. */
internal data class RawReadingToken(
    val sourceStart: Int,
    val sourceEnd: Int,
    val readingForm: String,
    val isParticle: Boolean = false,
)

/** Pure function, so malformed token offsets and unsupported readings can be regression-tested. */
internal object SourceReadingAssembler {
    private val phraseSeparators = "、。，．.!?！？・/()[]「」『』｡､｢｣･"
    private fun isSeparator(char: Char): Boolean = char.isWhitespace() || char in phraseSeparators
    private val english = mapOf(
        "i" to "아이", "you" to "유", "me" to "미", "my" to "마이",
        "we" to "위", "your" to "유어", "love" to "러브", "baby" to "베이비",
        "darling" to "달링", "no" to "노", "yes" to "예스", "oh" to "오",
        "hey" to "헤이", "yeah" to "예", "hello" to "헬로", "goodbye" to "굿바이",
        "forever" to "포에버", "night" to "나이트", "day" to "데이",
        "heart" to "하트", "world" to "월드", "dream" to "드림",
    )

    /** Sudachi offsets are UTF-16, but a Han glyph may occupy two Char units.
     * Neither a surrogate half nor an orphan Japanese voicing mark may begin
     * a token: the latter changes the sound of the preceding Kana base. */
    private fun isCodePointBoundary(text: String, offset: Int): Boolean =
        offset in 0..text.length && !(offset > 0 && offset < text.length &&
            Character.isHighSurrogate(text[offset - 1]) &&
            Character.isLowSurrogate(text[offset])) &&
            !(offset > 0 && offset < text.length &&
                JapaneseToHangul.isJapaneseVoicingMark(Character.codePointAt(text, offset)))

    private inline fun String.anyCodePoint(predicate: (Int) -> Boolean): Boolean {
        var offset = 0
        while (offset < length) {
            val cp = Character.codePointAt(this, offset)
            if (predicate(cp)) return true
            offset += Character.charCount(cp)
        }
        return false
    }

    /** Marks are source-significant even when Unicode classifies them as
     * combining punctuation; they may not disappear into an uncovered gap. */
    private fun String.hasSourceContent(): Boolean = anyCodePoint(JapaneseToHangul::isLyricSourceContent)

    private fun String.hasSpokenBase(): Boolean = JapaneseToHangul.containsSpokenSourceBase(this)

    private fun String.letterKey(): String = buildString {
        var offset = 0
        while (offset < this@letterKey.length) {
            val cp = Character.codePointAt(this@letterKey, offset)
            if (JapaneseToHangul.isLyricSourceContent(cp)) appendCodePoint(cp)
            offset += Character.charCount(cp)
        }
    }

    private fun isHan(cp: Int): Boolean =
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun String.hasHan(): Boolean = anyCodePoint(::isHan)

    private fun String.hasJapanese(): Boolean = anyCodePoint { cp ->
        cp in 0x3040..0x30ff || cp in 0xff66..0xff9f || cp == 0x3005 || isHan(cp)
    }

    private fun String.hasUncoveredLetters(coverage: BooleanArray): Boolean {
        var offset = 0
        while (offset < length) {
            val cp = Character.codePointAt(this, offset)
            val end = offset + Character.charCount(cp)
            if (JapaneseToHangul.isLyricSourceContent(cp) &&
                (offset until end).any { it >= coverage.size || !coverage[it] }
            ) return true
            offset = end
        }
        return false
    }

    /** Native text can omit whitespace/punctuation, but must never omit,
     * reorder or duplicate any source letter. All ranges use UTF-16 offsets. */
    private fun nativeWordRanges(source: String, nativeWords: List<String>): List<IntRange>? {
        if (nativeWords.isEmpty()) return null
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        var verifiedWords = 0
        for (word in nativeWords) {
            if (word.isEmpty()) continue
            val index = source.indexOf(word, cursor)
            if (index < cursor || index < 0 ||
                !isCodePointBoundary(source, index) ||
                !isCodePointBoundary(source, index + word.length) ||
                source.substring(cursor, index).hasSourceContent()
            ) return null
            cursor = index + word.length
            ranges += index until cursor
            if (word.hasSpokenBase()) {
                verifiedWords++
            }
        }
        if (verifiedWords < 2 || source.substring(cursor).hasSourceContent()) return null
        return ranges
    }

    /** The source offsets of authentic Apple word boundaries. */
    private fun nativeWordBoundaries(source: String, nativeWords: List<String>): Set<Int>? {
        val ranges = nativeWordRanges(source, nativeWords) ?: return null
        val boundaries = mutableSetOf<Int>()
        for (range in ranges) {
            if (!source.substring(range.first, range.last + 1).hasSpokenBase()) continue
            boundaries += range.first
            boundaries += range.last + 1
        }
        boundaries.remove(0)
        boundaries.remove(source.length)
        return boundaries.takeIf { it.isNotEmpty() }
    }

    /** Last evidence check when even A-mode cannot split a Kanji compound.
     * Independently reading each authentic native cell is accepted ONLY when
     * ALL dictionary-proven cells rejoin the existing full-context sound.
     * Kana and grammatical particles are left to the contextual tokenizer.
     * A small bounded budget prevents a difficult line causing N² analysis. */
    fun refineCoarseNativeWords(
        source: String,
        nativeWords: List<String>,
        fullLine: LineReadingResult,
        analyzeCell: (String) -> LineReadingResult,
    ): LineReadingResult {
        if (fullLine.status != LineReadingStatus.COMPLETE) return fullLine
        val ranges = nativeWordRanges(source, nativeWords) ?: return fullLine
        var remainingProbes = 16
        var changed = false
        val refined = mutableListOf<SourceReadingSpan>()
        for (span in fullLine.spans) {
            val parts = ranges.filter { range ->
                range.first >= span.sourceStart && range.last + 1 <= span.sourceEnd &&
                    source.substring(range.first, range.last + 1).hasSpokenBase()
            }
            val completeCells = parts.size in 2..8 && parts.size <= remainingProbes &&
                parts.first().first == span.sourceStart &&
                parts.last().last + 1 == span.sourceEnd &&
                parts.joinToString("") { range ->
                    source.substring(range.first, range.last + 1).letterKey()
                } == span.sourceText.letterKey()
            if (!completeCells || !span.sourceText.hasHan()) {
                refined += span
                continue
            }
            remainingProbes -= parts.size
            val cellProofs = parts.map { range ->
                val surface = source.substring(range.first, range.last + 1)
                val result = runCatching { analyzeCell(surface) }.getOrNull()
                if (result == null || result.status != LineReadingStatus.COMPLETE || result.spans.isEmpty()) {
                    null
                } else {
                    val coverage = BooleanArray(surface.length)
                    var lastEnd = 0
                    val verified = result.spans.all { token ->
                        val valid = token.sourceStart >= lastEnd && token.sourceEnd > token.sourceStart &&
                            token.sourceEnd <= surface.length &&
                            isCodePointBoundary(surface, token.sourceStart) &&
                            isCodePointBoundary(surface, token.sourceEnd) &&
                            surface.substring(token.sourceStart, token.sourceEnd) == token.sourceText &&
                            token.hangul.any { it in '가'..'힣' }
                        if (valid) {
                            for (offset in token.sourceStart until token.sourceEnd) coverage[offset] = true
                            lastEnd = token.sourceEnd
                        }
                        valid
                    } && !surface.hasUncoveredLetters(coverage)
                    if (!verified) null else result.spans.map { token ->
                        token.copy(
                            sourceStart = range.first + token.sourceStart,
                            sourceEnd = range.first + token.sourceEnd,
                        )
                    }
                }
            }
            val pieces = cellProofs.filterNotNull().flatten()
            val sameFullLineSound = cellProofs.size == cellProofs.count { it != null } &&
                pieces.joinToString("") { it.hangul.filter { char -> char in '가'..'힣' } } ==
                    span.hangul.filter { it in '가'..'힣' }
            if (sameFullLineSound) {
                refined += pieces
                changed = true
            } else refined += span
        }
        if (!changed || refined.joinToString("") { it.hangul.filter { c -> c in '가'..'힣' } } !=
            fullLine.spans.joinToString("") { it.hangul.filter { c -> c in '가'..'힣' } }
        ) return fullLine
        return fullLine.copy(spans = refined)
    }

    /** A host TextView may soft-wrap *inside* one Apple native word. Its
     * visual glyph boundaries are unknown while this offline analysis runs.
     * Check potential Japanese character boundaries in advance using the
     * same strict whole-context sound proof as real native word boundaries.
     * Unicode code points, not UTF-16 halves, form the test cells. */
    fun refinePotentialVisualLineBoundaries(
        source: String,
        fullLine: LineReadingResult,
        analyzeCell: (String) -> LineReadingResult,
    ): LineReadingResult {
        if (fullLine.status != LineReadingStatus.COMPLETE ||
            fullLine.spans.none { span ->
                span.sourceText.codePointCount(0, span.sourceText.length) > 1 &&
                    span.sourceText.hasHan()
            }
        ) return fullLine
        val characterCells = mutableListOf<String>()
        var cursor = 0
        while (cursor < source.length) {
            val width = Character.charCount(Character.codePointAt(source, cursor))
            characterCells += source.substring(cursor, cursor + width)
            cursor += width
        }
        return refineCoarseNativeWords(source, characterCells, fullLine, analyzeCell)
    }

    /** The C-mode dictionary often groups words that Apple times independently.
     * B/A-mode is safe only if the ENTIRE source remains readable, every spoken
     * Hangul syllable is unchanged and more boundaries match Apple's own words.
     * This never estimates a Kanji reading or a karaoke syllable timestamp. */
    fun retryModesForNativeWords(
        source: String,
        nativeWords: List<String>,
        modeCount: Int,
        tokenize: (Int) -> List<RawReadingToken>,
    ): LineReadingResult {
        val nativeBoundaries = nativeWordBoundaries(source, nativeWords)
            ?: return retryModes(source, modeCount, tokenize)
        fun score(reading: LineReadingResult): Int {
            val splits = reading.spans.flatMap { listOf(it.sourceStart, it.sourceEnd) }.toSet()
            return nativeBoundaries.count { it in splits }
        }
        var firstComplete: LineReadingResult? = null
        var winner: LineReadingResult? = null
        var bestScore = -1
        var failure = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        var bestVerifiedCoverage = -1
        for (mode in 0 until modeCount) {
            val candidate = runCatching { assemble(source, tokenize(mode)) }.getOrNull()
                ?: continue
            if (candidate.status != LineReadingStatus.COMPLETE) {
                val coverage = candidate.spans.sumOf { it.sourceEnd - it.sourceStart }
                if (coverage > bestVerifiedCoverage) {
                    failure = candidate
                    bestVerifiedCoverage = coverage
                }
                continue
            }
            if (firstComplete == null) firstComplete = candidate
            val expected = firstComplete?.text.orEmpty().filter(Char::isLetterOrDigit)
            if (candidate.text.orEmpty().filter(Char::isLetterOrDigit) != expected) continue
            val candidateScore = score(candidate)
            if (candidateScore > bestScore) {
                winner = candidate
                bestScore = candidateScore
            }
            // Every Apple word boundary is already verified: additional
            // dictionary passes would waste CPU without improving karaoke.
            if (bestScore == nativeBoundaries.size) break
        }
        val selected = winner ?: return failure
        // Spacing stays identical to the earliest successful full-line read.
        // Only proven source↔Hangul token boundaries are refined.
        return selected.copy(text = firstComplete?.text)
    }

    /** Sudachi's longer C-mode unknown compounds sometimes have valid B/A-mode readings. */
    fun retryModes(
        source: String,
        modeCount: Int,
        tokenize: (Int) -> List<RawReadingToken>,
    ): LineReadingResult {
        if (source.isBlank()) return LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        var failure = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        var bestVerifiedCoverage = -1
        for (mode in 0 until modeCount) {
            val analyzed = runCatching { assemble(source, tokenize(mode)) }.getOrNull()
                ?: continue
            if (analyzed.status == LineReadingStatus.COMPLETE) return analyzed
            // Never let the last, worse dictionary split destroy earlier *verified*
            // source anchors. These spans are not a full-line pronunciation and
            // must still be presented only through the explicitly partial path.
            val verifiedCoverage = analyzed.spans.sumOf { it.sourceEnd - it.sourceStart }
            if (verifiedCoverage > bestVerifiedCoverage) {
                failure = analyzed
                bestVerifiedCoverage = verifiedCoverage
            }
        }
        return failure
    }

    fun assemble(source: String, tokens: List<RawReadingToken>): LineReadingResult {
        if (source.isBlank()) return LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        val result = StringBuilder()
        val spans = mutableListOf<SourceReadingSpan>()
        var previousEnd = 0
        var pendingSpace = false
        for (token in tokens) {
            if (token.sourceStart < previousEnd || token.sourceStart < 0 ||
                token.sourceEnd <= token.sourceStart || token.sourceEnd > source.length ||
                !isCodePointBoundary(source, token.sourceStart) ||
                !isCodePointBoundary(source, token.sourceEnd)
            ) return unsupported(spans)
            val gap = source.substring(previousEnd, token.sourceStart)
            if (gap.hasSourceContent()) return unsupported(spans)
            if (gap.any(::isSeparator)) pendingSpace = true
            val surface = source.substring(token.sourceStart, token.sourceEnd)
            if (surface.all { it == 'ー' || it == 'ｰ' }) {
                // Sudachi can tokenize the katakana length mark on its own.
                // A length mark has no separate Hangul syllable; it extends
                // the preceding contiguous Japanese source-reading span.
                val preceding = spans.lastOrNull()
                    ?.takeIf {
                        it.sourceEnd == token.sourceStart && it.sourceText.hasJapanese()
                    }
                    ?: return unsupported(spans)
                spans[spans.lastIndex] = preceding.copy(
                    sourceEnd = token.sourceEnd,
                    sourceText = source.substring(preceding.sourceStart, token.sourceEnd),
                    kana = preceding.kana + surface,
                )
                previousEnd = token.sourceEnd
                continue
            }
            // '・' is in the Unicode Katakana block, but is punctuation rather
            // than an unreadable Japanese word. Sudachi can emit it separately.
            if (surface.all(::isSeparator)) {
                pendingSpace = true
                previousEnd = token.sourceEnd
                continue
            }
            // A combining/spacing/halfwidth dakuten is source content, not
            // an independently pronounceable Japanese character. Even a
            // dictionary-provided reading cannot make an orphan mark sound.
            if (surface.hasSourceContent() && !surface.hasSpokenBase()) {
                return unsupported(spans)
            }
            val hasJapanese = surface.hasJapanese()
            val sound = when {
                hasJapanese -> {
                    val kana = when {
                        token.isParticle && surface == "は" -> "ワ"
                        token.isParticle && surface == "へ" -> "エ"
                        token.isParticle && surface == "を" -> "オ"
                        token.readingForm.isNotBlank() && token.readingForm != "*" -> token.readingForm
                        surface.all { it in '\u3040'..'\u30ff' || it in '\uFF66'..'\uFF9F' } -> surface
                        else -> return unsupported(spans)
                    }
                    val hangul = JapaneseToHangul.fromKana(kana) ?: return unsupported(spans)
                    spans += SourceReadingSpan(token.sourceStart, token.sourceEnd, surface, kana, hangul)
                    hangul
                }
                surface.all { it in 'A'..'Z' || it in 'a'..'z' } -> {
                    val hangul = english[surface.lowercase()] ?: return unsupported(spans)
                    spans += SourceReadingSpan(token.sourceStart, token.sourceEnd, surface, surface, hangul)
                    hangul
                }
                surface.hasSourceContent() -> return unsupported(spans)
                else -> null
            }
            if (sound != null) {
                if (result.isNotEmpty() && pendingSpace) result.append(' ')
                result.append(sound)
                pendingSpace = false
            } else if (surface.any(::isSeparator)) {
                // Sudachi may emit punctuation/space as its own token. Its
                // source gap is then empty for the next sounding token.
                pendingSpace = true
            }
            previousEnd = token.sourceEnd
        }
        if (source.substring(previousEnd).hasSourceContent()) return unsupported(spans)
        val value = result.toString().trim().takeIf(String::isNotEmpty)
        return if (value == null) LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        else LineReadingResult(value, spans, LineReadingStatus.COMPLETE)
    }

    private fun unsupported(spans: List<SourceReadingSpan>) =
        LineReadingResult(null, spans, LineReadingStatus.MISSING_UNSUPPORTED)
}
