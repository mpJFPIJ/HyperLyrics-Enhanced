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

    @Test fun splitMorphologicalTokenKeepsWholeVerifiedSoundOnItsFirstNativeCell() {
        val result = AppleWordReadingAlignment.align(
            "駄目", listOf("駄", "目"), listOf(span(0, 2, "駄目", "다메")),
        )
        assertTrue(result is WordAlignmentResult.Grouped)
        assertEquals(listOf("다메", ""),
            (result as WordAlignmentResult.Grouped).words.map { it.hangul })
        assertEquals("다메", result.words.joinToString("") { it.hangul })
    }

    @Test fun mixedWholeAndSplitTokensRetainExactSoundAndSourceOrder() {
        val result = AppleWordReadingAlignment.align(
            "駄目駄目脳みその中から",
            listOf("駄", "目", "駄目", "脳", "みそ", "の", "中", "から"),
            listOf(
                span(0, 2, "駄目", "다메"), span(2, 4, "駄目", "다메"),
                span(4, 5, "脳", "노"), span(5, 7, "みそ", "미소"),
                span(7, 8, "の", "노"), span(8, 9, "中", "나카"),
                span(9, 11, "から", "카라"),
            ),
            expectedHangul = "다메다메노미소노나카카라",
        )
        assertTrue(result is WordAlignmentResult.Grouped)
        val words = (result as WordAlignmentResult.Grouped).words
        assertEquals(listOf("다메", "", "다메", "노", "미소", "노", "나카", "카라"),
            words.map { it.hangul })
        assertEquals("다메다메노미소노나카카라", words.joinToString("") { it.hangul })
    }

    @Test fun nonSpokenJapaneseQuotationMarksDoNotBreakNativeWordMatching() {
        val result = AppleWordReadingAlignment.align(
            "駄目「やめろ馬鹿」と",
            listOf("駄目", "やめろ", "馬鹿", "と"),
            listOf(
                span(0, 2, "駄目", "다메"),
                span(3, 6, "やめろ", "야메로"),
                span(6, 8, "馬鹿", "바카"),
                span(9, 10, "と", "토"),
            ),
            expectedHangul = "다메야메로바카토",
        )
        assertTrue(result is WordAlignmentResult.Verified)
        assertEquals(listOf("다메", "야메로", "바카", "토"),
            (result as WordAlignmentResult.Verified).words.map { it.hangul })
    }

    @Test fun punctuationThatAppleKeepsAsANativeWordDoesNotDisappearFromAlignment() {
        val result = AppleWordReadingAlignment.align(
            "ラブ！と", listOf("ラブ", "！", "と"),
            listOf(span(0, 2, "ラブ", "라부"), span(3, 4, "と", "토")),
            expectedHangul = "라부토",
        )
        assertTrue(result is WordAlignmentResult.Verified)
        assertEquals(listOf("라부", "", "토"),
            (result as WordAlignmentResult.Verified).words.map { it.hangul })
    }

    @Test fun rejectingIncorrectSoundEvenWhenSourceGroupingMatches() {
        assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.SOUND_MISMATCH),
            AppleWordReadingAlignment.align(
                "駄目", listOf("駄", "目"), listOf(span(0, 2, "駄目", "다메")),
                expectedHangul = "키고",
            ))
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
