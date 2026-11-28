package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.common.diagnostics.LyricBuildIdentity
import com.juren233.hyperlyricsenhanced.lyric.model.LyricPronunciationSpan
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSourceAnchorCoverageDiagnosticsTest {
    @Test fun countsFullPartialAndAbsentAnchorsIndependentlyOfNativeWordAlignment() {
        val full = RichLyricLine(
            text = "駄目は", roma = "다메와", pronunciationStatus = "LINE_WIDE",
            pronunciationSpans = listOf(
                LyricPronunciationSpan(0, 1, "駄", "다"),
                LyricPronunciationSpan(1, 2, "目", "메"),
                LyricPronunciationSpan(2, 3, "は", "와"),
            ),
        )
        val partial = RichLyricLine(
            text = "世界の歌", roma = "세카이노우타", pronunciationStatus = "PARTIAL_SOURCE_ANCHORED",
            pronunciationSpans = listOf(
                LyricPronunciationSpan(2, 3, "の", "노"),
            ),
        )
        val absent = RichLyricLine(text = "恋煩い", roma = "코이와즈라이")
        val summary = AppleSourceAnchorCoverageDiagnostics.summarize(listOf(full, partial, absent))
        assertEquals(1, summary.full)
        assertEquals(1, summary.partial)
        assertEquals(1, summary.absent)
        assertEquals(0, summary.alignedWords)
        assertEquals(3, summary.full + summary.partial + summary.absent)
    }

    @Test fun mismatchedOrReorderedSoundCannotClaimCompleteVerifiedAnchorCoverage() {
        val bad = RichLyricLine(
            text = "世界", roma = "세카이",
            pronunciationSpans = listOf(
                LyricPronunciationSpan(0, 1, "世", "카이"),
                LyricPronunciationSpan(1, 2, "界", "세"),
            ),
        )
        val duplicate = bad.copy(pronunciationSpans = listOf(
            LyricPronunciationSpan(0, 1, "世", "세"),
            LyricPronunciationSpan(0, 2, "世界", "세카이"),
        ))
        assertEquals(2, AppleSourceAnchorCoverageDiagnostics.summarize(
            listOf(bad, duplicate),
        ).partial)
    }

    @Test fun supplementaryHanMustBeFullyCoveredBeforeReportingCompleteSourceAnchors() {
        val missingHan = RichLyricLine(
            text = "𠮟あ", roma = "아",
            pronunciationSpans = listOf(LyricPronunciationSpan(2, 3, "あ", "아")),
        )
        val splitSurrogate = RichLyricLine(
            text = "𠮟あ", roma = "시아",
            pronunciationSpans = listOf(
                LyricPronunciationSpan(0, 1, "\uD842", "시"),
                LyricPronunciationSpan(2, 3, "あ", "아"),
            ),
        )
        val complete = RichLyricLine(
            text = "𠮟あ", roma = "시카아",
            pronunciationSpans = listOf(
                LyricPronunciationSpan(0, 2, "𠮟", "시카"),
                LyricPronunciationSpan(2, 3, "あ", "아"),
            ),
        )
        val evidence = AppleSourceAnchorCoverageDiagnostics.summarize(listOf(
            missingHan, splitSurrogate, complete,
        ))
        assertEquals(1, evidence.full)
        assertEquals(2, evidence.partial)
    }

    @Test fun provenanceMetricsAreNumbersAndNeverExposeLyricContent() {
        val stats = LyricBuildIdentity.safeStage("reading_finished", mapOf(
            "fullSourceSpanLines" to 2,
            "partialSourceSpanLines" to 19,
            "missingSourceSpanLines" to 1,
            "unknownLyricSecret" to 123,
        ))
        assertTrue(stats.contains("fullSourceSpanLines=2"))
        assertTrue(stats.contains("partialSourceSpanLines=19"))
        assertTrue(stats.contains("missingSourceSpanLines=1"))
        assertFalse(stats.contains("unknownLyricSecret"))
    }
}
