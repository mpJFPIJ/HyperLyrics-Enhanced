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

    @Test fun supplementaryHanNativeWordRetainsItsVerifiedReadingAndUtf16Offsets() {
        val result = AppleWordReadingAlignment.align(
            "𠮟あ", listOf("𠮟", "あ"), listOf(
                span(0, 2, "𠮟", "시츠"), span(2, 3, "あ", "아"),
            ), expectedHangul = "시츠아",
        )
        assertTrue(result is WordAlignmentResult.Verified)
        assertEquals(listOf("시츠", "아"),
            (result as WordAlignmentResult.Verified).words.map { it.hangul })
    }

    @Test fun missingSupplementaryHanCannotBecomeAnEmptyVerifiedNativeWord() {
        assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS),
            AppleWordReadingAlignment.align(
                "𠮟あ", listOf("𠮟", "あ"), listOf(span(2, 3, "あ", "아")),
                expectedHangul = "아",
            ))
    }

    @Test fun nativeProjectionCannotSkipVoicingMarkBetweenAppleWordCells() {
        for (mark in listOf("\u3099", "\u309A", "\u309B", "\u309C", "\uFF9E", "\uFF9F")) {
            val original = "か${mark}て"
            assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
                original = original, appleWords = listOf("か", "て"),
                spans = listOf(
                    span(0, 1 + mark.length, "か${mark}", "가"),
                    span(1 + mark.length, original.length, "て", "테"),
                ), pronunciation = "가테",
            ))
        }
    }

    @Test fun nativeAlignmentRejectsUncoveredVoicingMarkAndMalformedKoreanSound() {
        for (mark in listOf("\u3099", "\u309A", "\u309B", "\u309C", "\uFF9E", "\uFF9F")) {
            val original = "か${mark}"
            assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS),
                AppleWordReadingAlignment.align(
                    original, listOf(original), listOf(span(0, 1, "か", "카")), "카",
                ))
            assertTrue(AppleWordReadingAlignment.align(
                original, listOf(original), listOf(
                    span(0, original.length, original, "가"),
                ), "가${mark}",
            ) is WordAlignmentResult.LineWide)
            assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
                original = original, appleWords = listOf(original),
                spans = listOf(span(0, original.length, original, "가")),
                pronunciation = "가${mark}",
            ))
        }
    }

    @Test fun genuineVoicedNativeWordKeepsItsExactAnchoredSound() {
        for ((original, firstEnd) in listOf("か\u3099て" to 2, "ｶﾞて" to 2)) {
            val first = original.substring(0, firstEnd)
            val native = AppleNativeReadingProjection.projectForNativeKaraoke(
                original = original, appleWords = listOf(first, "て"),
                spans = listOf(
                    span(0, firstEnd, first, "가"),
                    span(firstEnd, original.length, "て", "테"),
                ), pronunciation = "가테",
            )
            assertEquals(listOf("가", "테"), native?.words?.map { it.hangul })
            assertEquals(false, native?.approximate)
        }
    }

    @Test fun separatorCannotHideWrongResidualKanaOnNativeWordClock() {
        for (source in listOf("あ こ", "あ、こ", "あ！こ")) {
            assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
                original = source, appleWords = listOf("あ", "こ"),
                spans = emptyList(), pronunciation = "아카",
            ))
            val correct = AppleNativeReadingProjection.projectForNativeKaraoke(
                original = source, appleWords = listOf("あ", "こ"),
                spans = emptyList(), pronunciation = "아코",
            )
            assertEquals(listOf("아", "코"), correct?.words?.map { it.hangul })
            assertEquals(false, correct?.approximate)
        }
        // A Kanji-only residual occupying one actual native word can still
        // inherit its indivisible word window without a fabricated split.
        val unknown = AppleNativeReadingProjection.projectForNativeKaraoke(
            original = "愛 こ", appleWords = listOf("愛", "こ"),
            spans = emptyList(), pronunciation = "아이코",
        )
        assertEquals(listOf("아이", "코"), unknown?.words?.map { it.hangul })
        assertEquals(true, unknown?.approximate)
    }

    @Test fun nativeOrPronunciationSpanCannotBisectASupplementaryHan() {
        val source = "𠮟あ"
        val half = source.substring(0, 1)
        assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.SOURCE_MISMATCH),
            AppleWordReadingAlignment.align(source, listOf(half, source.substring(1)),
                listOf(span(0, 2, "𠮟", "시츠"), span(2, 3, "あ", "아")),
                expectedHangul = "시츠아",
            ))
    }

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

    @Test fun singleKanjiResidualWithIndependentlyReadableKanaUsesNativeWordClocks() {
        val examples = listOf(
            Triple("脳みそ", listOf("脳", "みそ"), listOf("노", "미소")),
            Triple("喚く", listOf("喚", "く"), listOf("와메", "쿠")),
            Triple("溺れ", listOf("溺", "れ"), listOf("오보", "레")),
        )
        examples.forEach { (source, words, expected) ->
            val whole = expected.joinToString("")
            val result = AppleWordReadingAlignment.align(
                source, words, listOf(span(0, source.length, source, whole)), whole,
            )
            assertTrue("$source must have a verified boundary: $result",
                result is WordAlignmentResult.Verified)
            assertEquals(expected, (result as WordAlignmentResult.Verified).words.map { it.hangul })
        }
    }

    @Test fun residualKanjiCannotAllocateNonSyllabicHangulToNativeCells() {
        val result = AppleWordReadingAlignment.align(
            "脳みそ", listOf("脳", "みそ"),
            listOf(span(0, 3, "脳みそ", "ㄴ미소")), expectedHangul = "ㄴ미소",
        )
        assertTrue(result !is WordAlignmentResult.Verified)
    }

    @Test fun twoUnknownKanjiPiecesDoNotBecomeArtificialNativeWordBoundaries() {
        val result = AppleWordReadingAlignment.align(
            "世界", listOf("世", "界"), listOf(span(0, 2, "世界", "세카이")),
        )
        assertTrue(result is WordAlignmentResult.Grouped)
    }

    @Test fun mixedJapaneseLineCanSplitLiteralKanaOnlyWhenIndependentReadingsRejoinExactly() {
        val result = AppleWordReadingAlignment.align(
            "駄目あい", listOf("駄目", "あ", "い"),
            listOf(span(0, 2, "駄目", "다메"), span(2, 4, "あい", "아이")),
            expectedHangul = "다메아이",
        )
        assertTrue(result is WordAlignmentResult.Verified)
        assertEquals(listOf("다메", "아", "이"),
            (result as WordAlignmentResult.Verified).words.map { it.hangul })
    }

    @Test fun kanaDigraphCannotBeSplitByNativeWordClocksWhenReadingsChange() {
        val result = AppleWordReadingAlignment.align(
            "駄目きゃ", listOf("駄目", "き", "ゃ"),
            listOf(span(0, 2, "駄目", "다메"), span(2, 4, "きゃ", "캬")),
            expectedHangul = "다메캬",
        )
        assertTrue(result is WordAlignmentResult.Grouped)
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

    @Test fun punctuationOnlyPhoneticSpanCannotMasqueradeAsASungNativeWord() {
        assertEquals(WordAlignmentResult.LineWide(AlignmentFailure.INVALID_SPANS),
            AppleWordReadingAlignment.align(
                "あ!い", listOf("あ", "!", "い"),
                listOf(
                    span(0, 1, "あ", "아"),
                    span(1, 2, "!", "나"),
                    span(2, 3, "い", "이"),
                ), expectedHangul = "아나이",
            ))
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
