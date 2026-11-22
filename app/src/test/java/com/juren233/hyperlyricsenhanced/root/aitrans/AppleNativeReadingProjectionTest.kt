package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeReadingProjectionTest {
    @Test fun partialSudachiAnchorsKeepTheirEntireSoundOnTheCorrectAppleWord() {
        val original = "君の名は"
        val wordCells = listOf("君", "の", "名は")
        val sound = "키미노나와"
        val blind = AppleNativeReadingProjection.projectApproximate(
            original, wordCells, sound,
        )
        // Blind length-proportional splitting is wrong for the kanji 君:
        // two Hangul syllables belong to its single Japanese glyph.
        assertEquals("키", blind?.words?.first()?.hangul)
        val anchored = AppleNativeReadingProjection.projectAnchored(
            original, wordCells,
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "키미")),
            sound,
        )
        assertEquals(listOf("키미", "노", "나와"), anchored?.words?.map { it.hangul })
        assertEquals(sound, anchored?.words?.joinToString("") { it.hangul })
    }

    @Test fun conflictingSourceAnchorCannotReassignUnrelatedSound() {
        assertNull(AppleNativeReadingProjection.projectAnchored(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "미소")),
            "키미노나와",
        ))
    }

    @Test fun aCompleteAIReadingWithoutSpansStillUsesNativeCells() {
        val result = AppleNativeReadingProjection.projectApproximate(
            original = "君の名は",
            appleWords = listOf("君", "の", "名は"),
            pronunciation = "키미노 나와",
        )
        assertTrue(result?.approximate == true)
        assertEquals(3, result?.words?.size)
        assertEquals("키미노나와", result?.words?.joinToString("") { it.hangul }
            ?.replace(AppleNativeReadingProjection.EMPTY_PRONUNCIATION_CELL, ""))
    }
    @Test fun mixedKanjiCompoundThatCrossesNativeCellsStillUsesNativeKaraoke() {
        val spans = listOf(
            SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
            SourceReadingSpan(2, 3, "脳", "ノウ", "노"),
            SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
        )
        // A compound is not independently proved at Apple's boundary. Keep
        // the source-anchored reading on Apple's *actual* word clocks, rather
        // than painting all Hangul after the last Japanese visual row.
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "駄目脳みそ", listOf("駄", "目", "脳", "みそ"), spans, "다메노미소",
        )
        assertEquals(listOf("다", "메", "노", "미소"),
            native?.words?.map { it.hangul })
        assertTrue(native?.approximate == true)
    }

    @Test fun partialSourceAnchorsKeepKanjiPronunciationOnTheCorrectNativeWord() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "키미")), "키미노나와",
        )
        assertEquals(listOf("키미", "노", "나와"), native?.words?.map { it.hangul })
    }

    @Test fun withoutDictionarySpansNativeWordsStillAvoidAWholeLineHangulBlock() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "やたらとしんどい恋", listOf("やたら", "と", "しんどい", "恋"),
            emptyList(), "야타라토신도이코이",
        )
        assertEquals(4, native?.words?.size)
        assertEquals("야타라토신도이코이", native?.words?.joinToString("") { it.hangul })
        assertTrue(native?.approximate == true)
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "やたらとしんどい恋", listOf("やたらとしんどい恋"),
            emptyList(), "야타라토신도이코이",
        ))
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "やたらとしんどい恋", listOf("別の", "歌"),
            emptyList(), "야타라토신도이코이",
        ))
    }
    @Test fun anUnrelatedSourceCannotReceiveARecycledJapanesePronunciation() {
        assertNull(AppleNativeReadingProjection.projectApproximate(
            original = "君の名は", appleWords = listOf("別の", "歌"),
            pronunciation = "키미노 나와",
        ))
    }
    @Test fun silentLongVowelCellCannotPrintJapaneseGlyph() {
        val result = AppleNativeReadingProjection.projectApproximate(
            original = "ダーリン", appleWords = listOf("ダ", "ー", "リン"),
            pronunciation = "다린",
        )
        assertTrue(result != null)
        assertEquals(3, result?.words?.size)
        assertEquals("다린", result?.words?.joinToString("") { it.hangul }?.replace(
            AppleNativeReadingProjection.EMPTY_PRONUNCIATION_CELL, "",
        ))
    }
    @Test fun groupedJapaneseMorphemeCannotInventAWordClockBoundary() {
        val result = AppleNativeReadingProjection.project(
            "駄目脳みそ", listOf("駄", "目", "脳", "みそ"),
            listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 3, "脳", "ノウ", "노"),
                SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
            ), "다메노미소",
        )
        // 駄目 is one verified sound. Neither 駄 nor 目 provides a verified
        // pronunciation breakpoint, even when Apple times the two glyphs.
        assertNull(result)
    }

    @Test fun verifiedKanaInsideMixedJapaneseLineUsesNativeWordClocksWithNoApproximation() {
        val result = AppleNativeReadingProjection.project(
            "駄目あい", listOf("駄目", "あ", "い"),
            listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 4, "あい", "アイ", "아이"),
            ), "다메아이",
        )
        assertEquals(listOf("다메", "아", "이"), result?.words?.map { it.hangul })
        assertFalse(result?.approximate ?: true)
    }
    @Test fun punctuationOnlyNativeCellsUseInvisiblePlaceholders() {
        val result = AppleNativeReadingProjection.project(
            "ラブ！と", listOf("ラブ", "！", "と"),
            listOf(
                SourceReadingSpan(0, 2, "ラブ", "ラブ", "라부"),
                SourceReadingSpan(3, 4, "と", "ト", "토"),
            ), "라부토",
        )
        assertEquals(listOf("라부", AppleNativeReadingProjection.EMPTY_PRONUNCIATION_CELL, "토"),
            result?.words?.map { it.hangul })
    }
    @Test fun oboreCannotLeaveItsFinalSyllableInTheNextNativeCell() {
        val result = AppleNativeReadingProjection.project(
            original = "溺れ死にそう",
            appleWords = listOf("溺", "れ", "死に", "そう"),
            spans = listOf(
                SourceReadingSpan(0, 2, "溺れ", "オボレ", "오보레"),
                SourceReadingSpan(2, 6, "死にそう", "シニソウ", "시니소"),
            ),
            expectedHangul = "오보레시니소",
        )
        // The whole line can be rendered, but the separate word cells have
        // no trustworthy place to divide 오보레 or 시니소.
        assertNull(result)
    }
    @Test fun repeatedVerifiedMorphemesRetainEverySoundAndCellIdentity() {
        val result = AppleNativeReadingProjection.project(
            original = "駄目駄目",
            appleWords = listOf("駄目", "駄目"),
            spans = listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 4, "駄目", "ダメ", "다메"),
            ),
            expectedHangul = "다메다메",
        )
        assertEquals(listOf("다메", "다메"), result?.words?.map { it.hangul })
        assertFalse(result?.approximate ?: true)
    }
    @Test fun neverProjectsUnverifiedSourceSound() {
        assertNull(AppleNativeReadingProjection.project(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "키미")),
            "키미노 나와",
        ))
    }
    @Test fun insufficientPhoneticSyllablesDoNotFakeOneForEveryAppleCell() {
        assertNull(AppleNativeReadingProjection.project(
            "ダーリン", listOf("ダ", "ー", "リ", "ン"),
            listOf(SourceReadingSpan(0, 4, "ダーリン", "ダーリン", "다린")),
            "다린",
        ))
    }
    @Test fun unchangedCompleteReadingIsNotMarkedEstimated() {
        val result = AppleNativeReadingProjection.project(
            "君の名は", listOf("君", "の", "名は"),
            listOf(
                SourceReadingSpan(0, 1, "君", "キミ", "키미"),
                SourceReadingSpan(1, 2, "の", "ノ", "노"),
                SourceReadingSpan(2, 4, "名は", "ナワ", "나와"),
            ), "키미노나와",
        )
        assertFalse(result?.approximate ?: true)
        assertEquals(listOf("키미", "노", "나와"), result?.words?.map { it.hangul })
    }
}
