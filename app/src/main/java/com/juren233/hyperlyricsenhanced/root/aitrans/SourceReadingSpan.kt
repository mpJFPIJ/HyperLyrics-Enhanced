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
    private val phraseSeparators = "、。，．.!?！？・/()[]「」『』"
    private fun isSeparator(char: Char): Boolean = char.isWhitespace() || char in phraseSeparators
    private val english = mapOf(
        "i" to "아이", "you" to "유", "me" to "미", "my" to "마이",
        "we" to "위", "your" to "유어", "love" to "러브", "baby" to "베이비",
        "darling" to "달링", "no" to "노", "yes" to "예스", "oh" to "오",
        "hey" to "헤이", "yeah" to "예", "hello" to "헬로", "goodbye" to "굿바이",
        "forever" to "포에버", "night" to "나이트", "day" to "데이",
        "heart" to "하트", "world" to "월드", "dream" to "드림",
    )

    /** Sudachi's longer C-mode unknown compounds sometimes have valid B/A-mode readings. */
    fun retryModes(
        source: String,
        modeCount: Int,
        tokenize: (Int) -> List<RawReadingToken>,
    ): LineReadingResult {
        if (source.isBlank()) return LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        var failure = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        for (mode in 0 until modeCount) {
            val analyzed = runCatching { assemble(source, tokenize(mode)) }.getOrNull()
                ?: continue
            if (analyzed.status == LineReadingStatus.COMPLETE) return analyzed
            failure = analyzed
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
                token.sourceEnd <= token.sourceStart || token.sourceEnd > source.length
            ) return unsupported(spans)
            val gap = source.substring(previousEnd, token.sourceStart)
            if (gap.any(Char::isLetterOrDigit)) return unsupported(spans)
            if (gap.any(::isSeparator)) pendingSpace = true
            val surface = source.substring(token.sourceStart, token.sourceEnd)
            if (surface.all { it == 'ー' }) {
                // Sudachi can tokenize the katakana length mark on its own.
                // A length mark has no separate Hangul syllable; it extends
                // the preceding contiguous Japanese source-reading span.
                val preceding = spans.lastOrNull()
                    ?.takeIf {
                        it.sourceEnd == token.sourceStart && it.sourceText.any(::isJapanese)
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
            val hasJapanese = surface.any(::isJapanese)
            val sound = when {
                hasJapanese -> {
                    val kana = when {
                        token.isParticle && surface == "は" -> "ワ"
                        token.isParticle && surface == "へ" -> "エ"
                        token.isParticle && surface == "を" -> "オ"
                        token.readingForm.isNotBlank() && token.readingForm != "*" -> token.readingForm
                        surface.all { it in '\u3040'..'\u30ff' } -> surface
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
                surface.any(Char::isLetterOrDigit) -> return unsupported(spans)
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
        if (source.substring(previousEnd).any(Char::isLetterOrDigit)) return unsupported(spans)
        val value = result.toString().trim().takeIf(String::isNotEmpty)
        return if (value == null) LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        else LineReadingResult(value, spans, LineReadingStatus.COMPLETE)
    }

    private fun unsupported(spans: List<SourceReadingSpan>) =
        LineReadingResult(null, spans, LineReadingStatus.MISSING_UNSUPPORTED)

    private fun isJapanese(char: Char): Boolean = char in '\u3040'..'\u30ff' ||
        char in '\u3400'..'\u9fff' || char == '\u3005'
}
