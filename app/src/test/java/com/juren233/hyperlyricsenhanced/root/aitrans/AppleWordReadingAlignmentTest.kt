package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleWordReadingAlignmentTest {
    private fun span(start: Int, end: Int, text: String, hangul: String) =
        SourceReadingSpan(start, end, text, text, hangul)

    @Test fun exactlyContainedSpansFollowNativeWordBoundariesWithoutTimingGuess() {
        val result = AppleWordReadingAlignment.align(
            "駄目駄目脳みそ", listOf("駄目", "駄目", "脳", "みそ"),
            listOf(
                span(0, 2, "駄目", "다메"), span(2, 4, "駄目", "다메"),
                span(4, 5, "脳", "노"), span(5, 7, "みそ", "미소"),
            ),
        )
        assertTrue(result is WordAlignmentResult.Verified)
        assertEquals(listOf("다메", "다메", "노", "미소"),
            (result as WordAlignmentResult.Verified).words.map { it.hangul })
    }

    @Test fun splitMorphologicalTokenIsAlwaysLineWide() {
        val result = AppleWordReadingAlignment.align(
            "駄目", listOf("駄", "目"), listOf(span(0, 2, "駄目", "다메")),
        )
        assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.BOUNDARY_SPLIT), result)
    }

    @Test fun overlappingOrForeignTextNeverProducesVerifiedWordMap() {
        assertTrue(AppleWordReadingAlignment.align(
            "君の名は", listOf("君", "の", "名", "は"),
            listOf(span(0, 1, "君", "키미"), span(1, 2, "의", "노")),
        ) is WordAlignmentResult.LineWide)
        assertTrue(AppleWordReadingAlignment.align(
            "駄目", listOf("駄目", "余計"), listOf(span(0, 2, "駄目", "다메")),
        ) is WordAlignmentResult.LineWide)
    }

    @Test fun repeatedWordsMatchInStableSourceOrderWithWhitespace() {
        val result = AppleWordReadingAlignment.align(
            "駄目 駄目", listOf("駄目", "駄目"),
            listOf(span(0, 2, "駄目", "다메"), span(3, 5, "駄目", "다메")),
        ) as WordAlignmentResult.Verified
        assertEquals(listOf("駄目", "駄目"), result.words.map { it.source })
    }

    @Test fun newOptionalTransportFieldsDecodeOldLyricsWithoutAnyMigration() {
        val legacy = Json.decodeFromString<RichLyricLine>("""{"begin":10,"end":20,"text":"駄目","roma":"다메"}""")
        assertNull(legacy.pronunciationSpans)
        assertNull(legacy.pronunciationStatus)
        val encoded = Json.encodeToString(legacy.copy(pronunciationStatus = "LINE_WIDE"))
        assertEquals("LINE_WIDE", Json.decodeFromString<RichLyricLine>(encoded).pronunciationStatus)
    }
}
