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
            return line.copy(
                roma = null,
                pronunciationWords = null,
                pronunciationSpans = null,
                pronunciationStatus = analyzed.status.name,
            )
        }

        val verified = requireNotNull(analyzed.text)
        val supplied = line.roma?.trim()?.takeIf(String::isNotBlank)
        // AppleMusicSourcePhonetics is the final app->Apple transport boundary.
        // Never replace locally verified syllables, but also never discard
        // natural AI spacing when both sources spell the exact same sounds.
        val reading = supplied?.takeIf { ai ->
            RomanizationPolicy.sameHangulSounds(ai, verified)
        } ?: verified
        val alignment = AppleWordReadingAlignment.align(
            original = sourceText,
            appleWords = line.words.orEmpty().map { it.text.orEmpty() },
            spans = analyzed.spans,
            expectedHangul = reading,
        )
        return line.copy(
            roma = reading,
            pronunciationWords = (alignment as? WordAlignmentResult.Verified)?.words,
            pronunciationSpans = analyzed.spans.map { span ->
                LyricPronunciationSpan(
                    start = span.sourceStart,
                    end = span.sourceEnd,
                    source = span.sourceText,
                    hangul = span.hangul,
                )
            },
            pronunciationStatus = if (alignment is WordAlignmentResult.Verified)
                "ALIGNED" else "LINE_WIDE",
        )
    }
}
