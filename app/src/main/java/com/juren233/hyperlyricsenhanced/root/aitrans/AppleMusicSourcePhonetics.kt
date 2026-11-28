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
        analyzed: LineReadingResult = LocalJapanesePronunciation.generateLine(
            sourceText,
            analyzer = { fullLine ->
                SudachiReadingEngine.analyzeLineForNativeWords(
                    fullLine, line.words.orEmpty().map { it.text.orEmpty() },
                )
            },
        ),
    ): RichLyricLine {
        if (analyzed.status != LineReadingStatus.COMPLETE) {
            // A single unknown Sudachi token must not erase a useful reading
            // returned with the same AI-translated line. Only use this as an
            // explicitly unverified fallback on mixed Japanese/kana text,
            // including a single-kana-plus-kanji line. Entirely unknown
            // kanji (e.g. 未知語 -> invented 키코) still fail closed.
            // Halfwidth voiced marks join the preceding Kana under NFKC;
            // counting them as extra syllabic source characters would make
            // the conservative AI fallback length gate reject valid text.
            val kanaCount = sourceText.count { character ->
                JapaneseToHangul.isLiteralKanaCodePoint(character.code) &&
                    character !in '\uFF9E'..'\uFF9F'
            }
            val containsJapaneseOrKorean = sourceText.codePoints().anyMatch { codePoint ->
                JapaneseToHangul.isLiteralKanaCodePoint(codePoint) ||
                    when (Character.UnicodeScript.of(codePoint)) {
                        Character.UnicodeScript.HAN,
                        Character.UnicodeScript.HIRAGANA,
                        Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL -> true
                        else -> false
                    }
            }
            val englishOnly = sourceText.any { it in 'a'..'z' || it in 'A'..'Z' } &&
                !containsJapaneseOrKorean
            val previousReading = RomanizationPolicy.sanitizeHangulSupplement(
                sourceText, line.roma,
            )?.takeIf { candidate ->
                (englishOnly || kanaCount >= 1 ||
                    line.pronunciationStatus == "AI_GENERATED_UNVERIFIED") &&
                    candidate.count { it in '가'..'힣' } >=
                        maxOf(1, kanaCount / 2)
            }
            val partialSpans = if (previousReading != null) analyzed.spans
                .filter { span ->
                    span.sourceStart >= 0 && span.sourceEnd <= sourceText.length &&
                        span.sourceEnd > span.sourceStart &&
                        sourceText.substring(span.sourceStart, span.sourceEnd) == span.sourceText
                } else emptyList()
            // Partial dictionary anchors can improve line-wide timing, but
            // the unreadable gap cannot be divided into native lyric words
            // without inventing syllable boundaries. The previous path did
            // exactly that and displayed e.g. "다 메" for one "駄目".
            return line.copy(
                roma = previousReading,
                pronunciationWords = null,
                // Keep verified local anchors even when another token was
                // unreadable. The Apple Music process has the *actual* native
                // word clock and can use these spans for a better projection.
                pronunciationSpans = partialSpans.map { span ->
                    LyricPronunciationSpan(
                        start = span.sourceStart, end = span.sourceEnd,
                        source = span.sourceText, hangul = span.hangul,
                    )
                },
                pronunciationStatus = when {
                    previousReading == null -> analyzed.status.name
                    partialSpans.isNotEmpty() && !line.words.isNullOrEmpty() ->
                        "PARTIAL_SOURCE_ANCHORED"
                    else -> "AI_FALLBACK_UNVERIFIED"
                },
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
        val projected = AppleNativeReadingProjection.project(
            original = sourceText,
            appleWords = line.words.orEmpty().map { it.text.orEmpty() },
            spans = analyzed.spans,
            expectedHangul = reading,
        )
        // Complete, validated source spans may cross Apple's cell boundaries.
        // They are the minimum evidence for word-level projection. Without
        // them, retain the natural Korean sentence for a line-wide reading.
        val renderable = projected
        return line.copy(
            roma = reading,
            pronunciationWords = renderable?.words,
            pronunciationSpans = analyzed.spans.map { span ->
                LyricPronunciationSpan(
                    start = span.sourceStart,
                    end = span.sourceEnd,
                    source = span.sourceText,
                    hangul = span.hangul,
                )
            },
            pronunciationStatus = when {
                renderable?.approximate == true -> "PROJECTED_NATIVE_WORDS"
                renderable != null -> "ALIGNED"
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
