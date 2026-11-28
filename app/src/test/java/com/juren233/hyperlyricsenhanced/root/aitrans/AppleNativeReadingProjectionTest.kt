package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeReadingProjectionTest {
    @Test fun verifiedKanjiResidualNowUsesOriginalWordHighlightAndClock() {
        val cases = listOf(
            Triple("脳みそ", listOf("脳", "みそ"), listOf("노", "미소")),
            Triple("喚く", listOf("喚", "く"), listOf("와메", "쿠")),
            Triple("溺れ", listOf("溺", "れ"), listOf("오보", "레")),
        )
        cases.forEach { (source, words, sounds) ->
            val full = sounds.joinToString("")
            val projected = AppleNativeReadingProjection.projectForNativeKaraoke(
                source, words,
                listOf(SourceReadingSpan(0, source.length, source, source, full)), full,
            )
            assertEquals("native cells for $source", sounds, projected?.words?.map { it.hangul })
            assertFalse(projected?.approximate ?: true)
        }
    }

    @Test fun aVerifiedSingleNativeCellNoLongerNeedsTheSecondWordGuard() {
        val result = AppleNativeReadingProjection.projectForNativeKaraoke(
            "溺れ", listOf("溺れ"),
            listOf(SourceReadingSpan(0, 2, "溺れ", "オボレ", "오보레")), "오보레",
        )
        assertEquals(listOf("오보레"), result?.words?.map { it.hangul })
        assertFalse(result?.approximate ?: true)
    }

    @Test fun anUnverifiedGapInsideOneCellDoesNotPretendToBeAProvenReading() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "世界", listOf("世界"),
            listOf(SourceReadingSpan(0, 1, "世", "セ", "세")), "세카이",
        ))
    }

    @Test fun aCorruptedSpanWithAnOrphanJamoCannotBeNormalizedIntoNativeClocks() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "脳みそ", listOf("脳", "みそ"),
            listOf(SourceReadingSpan(0, 3, "脳みそ", "ノウミソ", "ㄴ노미소")),
            "노미소",
        ))
    }

    @Test fun aMissingSupplementaryHanGlyphCannotBeTreatedAsSilentPunctuation() {
        val source = "脳𠮷みそ"
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            source, listOf("脳", "みそ"),
            listOf(SourceReadingSpan(0, source.length, source, "ノウヨシミソ", "노요시미소")),
            "노요시미소",
        ))
        assertNull(AppleNativeReadingProjection.projectAnchored(
            source, listOf("脳", "みそ"),
            listOf(SourceReadingSpan(0, source.length, source, "ノウヨシミソ", "노요시미소")),
            "노요시미소",
        ))
    }

    @Test fun verifiedDigraphAndTwoKanjiUnknownsStillCannotAcquireNativeClocks() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "きゃ", listOf("き", "ゃ"),
            listOf(SourceReadingSpan(0, 2, "きゃ", "キャ", "캬")), "캬",
        ))
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "世界", listOf("世", "界"),
            listOf(SourceReadingSpan(0, 2, "世界", "セカイ", "세카이")), "세카이",
        ))
    }

    @Test fun halfwidthKanaUsesOnlyTheCorrespondingNativeWordClocks() {
        val source = "ｶﾞﾊﾛｰ"
        val words = listOf("ｶﾞ", "ﾊﾛｰ")
        val noDictionary = AppleNativeReadingProjection.projectForNativeKaraoke(
            source, words, emptyList(), "가하로",
        )
        assertEquals(listOf("가", "하로"), noDictionary?.words?.map { it.hangul })
        assertFalse(noDictionary?.approximate ?: true)

        val dictionary = AppleNativeReadingProjection.projectForNativeKaraoke(
            source, words,
            listOf(SourceReadingSpan(0, source.length, source, source, "가하로")),
            "가하로",
        )
        assertEquals(listOf("가", "하로"), dictionary?.words?.map { it.hangul })
        assertFalse(dictionary?.approximate ?: true)
    }

    @Test fun halfwidthVoicingAndLongMarkCannotBeGivenInventedNativeClocks() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "ｶﾞ", listOf("ｶ", "ﾞ"), emptyList(), "가",
        ))
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "ｶﾞ", listOf("ｶ", "ﾞ"),
            listOf(SourceReadingSpan(0, 2, "ｶﾞ", "ガ", "가")), "가",
        ))
        val longMark = AppleNativeReadingProjection.projectForNativeKaraoke(
            "ﾀﾞｰﾘﾝ", listOf("ﾀﾞ", "ｰ", "ﾘﾝ"), emptyList(), "다린",
        )
        assertEquals(listOf("다", AppleNativeReadingProjection.EMPTY_PRONUNCIATION_CELL, "린"),
            longMark?.words?.map { it.hangul })
    }

    @Test fun unverifiedTopicParticleCannotStealTheFollowingKanjiReading() {
        // 鳥は羽 is read トリワハネ. A context-free は→하 guess must not
        // steal 羽's unique 하 and shift the Apple clocks to 토리와 / 하 / 네.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "鳥は羽", listOf("鳥", "は", "羽"), emptyList(), "토리와하네",
        ))
    }

    @Test fun unverifiedDirectionParticleCannotStealTheFollowingKanjiReading() {
        // へ as a directional particle reads 에, not its isolated 헤.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "鳥へ蛇", listOf("鳥", "へ", "蛇"), emptyList(), "토리에헤비",
        ))
    }

    @Test fun nativeCellSplitInsideLongKanaVowelCannotBorrowTheNextWordSound() {
        // The う in 行こう is silent after こ, whereas 海 starts with 우.
        // Unique substring matching alone can place 海's 우 on う's clock.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "行こう海", listOf("行こ", "う", "海"), emptyList(), "이코우미",
        ))
    }

    @Test fun verifiedParticleReadingKeepsItsCorrectAppleClock() {
        val result = AppleNativeReadingProjection.projectForNativeKaraoke(
            "鳥は羽", listOf("鳥", "は", "羽"),
            listOf(
                SourceReadingSpan(0, 1, "鳥", "トリ", "토리"),
                SourceReadingSpan(1, 2, "は", "ワ", "와"),
                SourceReadingSpan(2, 3, "羽", "ハネ", "하네"),
            ), "토리와하네",
        )
        assertEquals(listOf("토리", "와", "하네"), result?.words?.map { it.hangul })
        assertFalse(result?.approximate ?: true)
    }

    @Test fun contradictoryPartialSpansCannotMakeAnUnverifiedParticleSoundSafe() {
        // A partial provider may attribute the particle's 와 to 鳥 and
        // steal 羽's 하 for は. Exact full-line sound is still not proof.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "鳥は羽", listOf("鳥", "は", "羽"),
            listOf(
                SourceReadingSpan(0, 1, "鳥", "トリ", "토리와"),
                SourceReadingSpan(2, 3, "羽", "ハネ", "네"),
            ), "토리와하네",
        ))
    }

    @Test fun contradictoryPartialSpansCannotMakeASilentLongVowelSoundSafe() {
        // The isolated う is silent in こう; the next Kanji owns 우.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "行こう海", listOf("行こ", "う", "海"),
            listOf(
                SourceReadingSpan(0, 2, "行こ", "イコ", "이코"),
                SourceReadingSpan(3, 4, "海", "ウミ", "미"),
            ), "이코우미",
        ))
    }

    @Test fun noPhoneticProofCannotShiftOboreSyllablesIntoTheNextNativeClock() {
        // This is a real lyric shape from the reported wrapped karaoke line.
        // The complete Korean text establishes its overall sound, not the
        // Japanese-word boundary between 溺れ and 死にそう.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "瞳孔バチ開いて溺れ死にそう",
            listOf("瞳孔", "バチ", "開いて", "溺れ", "死にそう"),
            emptyList(), "도코 바치 히라이테 오보레 시니소",
        ))
    }

    @Test fun partialAnchorsCannotProportionallyCutTwoUnresolvedKanjiWords() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "瞳孔バチ開いて溺れ死にそう",
            listOf("瞳孔", "バチ", "開いて", "溺れ", "死にそう"),
            listOf(
                SourceReadingSpan(0, 2, "瞳孔", "ドコ", "도코"),
                SourceReadingSpan(2, 4, "バチ", "バチ", "바치"),
                SourceReadingSpan(4, 7, "開いて", "ヒライテ", "히라이테"),
            ),
            "도코 바치 히라이테 오보레 시니소",
        ))
    }

    @Test fun verifiedKanjiCompoundCannotBeSplitBySourceCharacterWeight() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "駄目脳みそ", listOf("駄", "目", "脳", "みそ"),
            listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 3, "脳", "ノ", "노"),
                SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
            ), "다메노미소",
        ))
    }

    @Test fun completeFinePhoneticProofPreservesEveryOriginalNativeWordClock() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "瞳孔バチ開いて溺れ死にそう",
            listOf("瞳孔", "バチ", "開いて", "溺れ", "死にそう"),
            listOf(
                SourceReadingSpan(0, 2, "瞳孔", "ドコ", "도코"),
                SourceReadingSpan(2, 4, "バチ", "バチ", "바치"),
                SourceReadingSpan(4, 7, "開いて", "ヒライテ", "히라이테"),
                SourceReadingSpan(7, 9, "溺れ", "オボレ", "오보레"),
                SourceReadingSpan(9, 13, "死にそう", "シニソウ", "시니소"),
            ), "도코 바치 히라이테 오보레 시니소",
        )
        assertEquals(listOf("도코", "바치", "히라이테", "오보레", "시니소"),
            native?.words?.map { it.hangul })
        assertFalse(native?.approximate ?: true)
    }

    @Test fun indivisibleKanaDigraphAndUnanchoredKanjiCannotClaimSplitClocks() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "きゃ", listOf("き", "ゃ"), emptyList(), "캬",
        ))
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "世界", listOf("世", "界"), emptyList(), "세카이",
        ))
    }

    @Test fun unspokenPunctuationCellCannotReceiveUnexplainedHangul() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "あ!い", listOf("あ", "!", "い"),
            listOf(
                SourceReadingSpan(0, 1, "あ", "ア", "아"),
                SourceReadingSpan(2, 3, "い", "イ", "이"),
            ), "아나이",
        ))
    }

    @Test fun fabricatedPunctuationPhoneticSpanCannotBypassTheBoundaryGuard() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "あ!い", listOf("あ", "!", "い"),
            listOf(
                SourceReadingSpan(0, 1, "あ", "ア", "아"),
                SourceReadingSpan(1, 2, "!", "", "나"),
                SourceReadingSpan(2, 3, "い", "イ", "이"),
            ), "아나이",
        ))
    }

    @Test fun isolatedUnverifiedKanaCellCannotOverrideItsKnownLiteralSound() {
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            "あこ", listOf("あ", "こ"),
            listOf(SourceReadingSpan(0, 1, "あ", "ア", "아")), "아카",
        ))
    }

    @Test fun partialSudachiAnchorsKeepTheirEntireSoundOnTheCorrectAppleWord() {
        val original = "君の名は"
        val wordCells = listOf("君", "の", "名は")
        val sound = "키미노나와"
        val blind = AppleNativeReadingProjection.projectApproximate(
            original, wordCells, sound,
        )
        // Even without the dictionary proof, an independently read kana
        // anchor isolates 君 as ONE whole native word: never cut its syllables.
        assertEquals("키미", blind?.words?.first()?.hangul)
        val anchored = AppleNativeReadingProjection.projectForNativeKaraoke(
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
    @Test fun mixedKanjiCompoundThatCrossesNativeCellsUsesIntactLineReading() {
        val spans = listOf(
            SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
            SourceReadingSpan(2, 3, "脳", "ノウ", "노"),
            SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
        )
        // A dictionary-proven compound sound is NOT a proven split at Apple's
        // native cells. The measured fallback must keep the sound intact.
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "駄目脳みそ", listOf("駄", "目", "脳", "みそ"), spans, "다메노미소",
        )
        assertNull(native)
    }

    @Test fun partialSourceAnchorsKeepKanjiPronunciationOnTheCorrectNativeWord() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "키미")), "키미노나와",
        )
        assertEquals(listOf("키미", "노", "나와"), native?.words?.map { it.hangul })
    }

    @Test fun aContradictoryPartialDictionarySpanCannotClaimNativeWordClocks() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "미소")), "키미노나와",
        )
        // A wrong dictionary anchor must not be silently discarded in favor
        // of a guessed proportional split; use the intact fallback instead.
        assertNull(native)
    }

    @Test fun withoutDictionarySpansNativeWordsStillAvoidAWholeLineHangulBlock() {
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "やたらとしんどい恋", listOf("やたら", "と", "しんどい", "恋"),
            emptyList(), "야타라토신도이코이",
        )
        assertEquals(4, native?.words?.size)
        // This is the exact problematic visual-row boundary from the sample
        // video. Kana words prove their OWN sounds; the remaining kanji gets
        // the two remaining syllables rather than stealing a kana syllable.
        assertEquals(listOf("야타라", "토", "신도이", "코이"),
            native?.words?.map { it.hangul })
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
        val native = AppleNativeReadingProjection.projectForNativeKaraoke(
            "ダーリン", listOf("ダ", "ー", "リン"), emptyList(), "다린",
        )
        assertEquals(listOf("다", AppleNativeReadingProjection.EMPTY_PRONUNCIATION_CELL, "린"),
            native?.words?.map { it.hangul })
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
    @Test fun oboreAndShinisouUseVerifiedKanaSuffixWithoutStealingNativeClocks() {
        val result = AppleNativeReadingProjection.project(
            original = "溺れ死にそう",
            appleWords = listOf("溺", "れ", "死に", "そう"),
            spans = listOf(
                SourceReadingSpan(0, 2, "溺れ", "オボレ", "오보레"),
                SourceReadingSpan(2, 6, "死にそう", "シニソウ", "시니소"),
            ),
            expectedHangul = "오보레시니소",
        )
        // れ and そう have independently reproducible complete sounds.
        // Only one Kanji-bearing cell remains in each verified whole token,
        // so no Hangul sound is borrowed from a neighboring native clock.
        assertEquals(listOf("오보", "레", "시니", "소"),
            result?.words?.map { it.hangul })
        assertFalse(result?.approximate ?: true)
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
