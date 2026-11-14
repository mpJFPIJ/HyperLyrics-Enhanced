package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.LyricPronunciationSpan
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine

/**
 * Recomputes the optional native supplement from the original source, independently
 * of inherited/third-party romanization and independently of the translation.
 * Equal inherited text is not evidence that this newly verified result is stale.
 */
internal object AppleMusicSourcePhonetics {
    fun attach(
        line: RichLyricLine,
        sourceText: String,
        analyzed: LineReadingResult = LocalJapanesePronunciation.generateLine(sourceText),
    ): RichLyricLine {
        if (analyzed.status != LineReadingStatus.COMPLETE) {
            // A single unknown Sudachi token must not erase a useful reading
            // returned with the same AI-translated line. Only use this as an
            // explicitly unverified fallback on mixed Japanese/kana text,
            // including a single-kana-plus-kanji line. Entirely unknown
            // kanji (e.g. 未知語 -> invented 키코) still fail closed.
            val kanaCount = sourceText.count { it in '\u3040'..'\u30ff' }
            val englishOnly = sourceText.any { it in 'a'..'z' || it in 'A'..'Z' } &&
                sourceText.none { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' ||
                    it in '가'..'힣' }
            val previousReading = RomanizationPolicy.sanitizeHangulSupplement(
                sourceText, line.roma,
            )?.takeIf { candidate ->
                (englishOnly || kanaCount >= 1) &&
                    candidate.count { it in '가'..'힣' } >=
                        maxOf(1, kanaCount / 2)
            }
            return line.copy(
                roma = previousReading,
                pronunciationWords = null,
                pronunciationSpans = null,
                pronunciationStatus = if (previousReading != null) "AI_FALLBACK_UNVERIFIED"
                    else analyzed.status.name,
            )
        }

        val verified = requireNotNull(analyzed.text)
        val supplied = line.roma?.trim()?.takeIf(String::isNotBlank)
        // AppleMusicSourcePhonetics is the final app->Apple transport boundary.
        // Never replace locally verified syllables, but also never discard
        // natural AI spacing when both sources spell the exact same sounds.
        val reading = supplied?.takeIf { ai ->
            RomanizationPolicy.sameHangulSounds(ai, verified) &&
                spacingRespectsSourceTokenBoundaries(ai, analyzed.spans)
        } ?: verified
        val alignment = AppleWordReadingAlignment.align(
            original = sourceText,
            appleWords = line.words.orEmpty().map { it.text.orEmpty() },
            spans = analyzed.spans,
            expectedHangul = reading,
        )
        val projected = AppleNativeReadingProjection.project(
            original = sourceText,
            appleWords = line.words.orEmpty().map { it.text.orEmpty() },
            spans = analyzed.spans,
            expectedHangul = reading,
        )
        return line.copy(
            roma = reading,
            pronunciationWords = projected?.words,
            pronunciationSpans = analyzed.spans.map { span ->
                LyricPronunciationSpan(
                    start = span.sourceStart,
                    end = span.sourceEnd,
                    source = span.sourceText,
                    hangul = span.hangul,
                )
            },
            pronunciationStatus = when {
                projected?.approximate == true -> "PROJECTED_NATIVE_WORDS"
                projected != null -> "ALIGNED"
                else -> "LINE_WIDE"
            },
        )
    }

    /** Do not render e.g. なか (나카) as "나 카" even if AI sounds are identical. */
    private fun spacingRespectsSourceTokenBoundaries(
        proposed: String,
        spans: List<SourceReadingSpan>,
    ): Boolean {
        if (spans.isEmpty()) return !proposed.any(Char::isWhitespace)
        var length = 0
        val boundaries = spans.dropLast(1).map { span ->
            length += span.hangul.count { !it.isWhitespace() }
            length
        }.toSet()
        var sounded = 0
        for (index in proposed.indices) {
            if (!proposed[index].isWhitespace()) {
                sounded++
            } else if (index > 0 && !proposed[index - 1].isWhitespace() &&
                index < proposed.lastIndex && sounded !in boundaries
            ) return false
        }
        return true
    }
}
