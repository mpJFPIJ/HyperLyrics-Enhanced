package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReadingSpanTest {
    @Test fun isolatedKatakanaLongVowelMarkerExtendsPriorSpanWithoutDroppingDarling() {
        val result = SourceReadingAssembler.assemble(
            "ダーリン",
            listOf(
                RawReadingToken(0, 1, "ダ"),
                RawReadingToken(1, 2, "*"),
                RawReadingToken(2, 4, "リン"),
            ),
        )
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("다린", result.text)
        assertEquals(listOf("ダー", "リン"), result.spans.map { it.sourceText })
    }

    @Test fun isolatedKatakanaMiddleDotIsPunctuationNotAnUnsupportedJapaneseWord() {
        val result = SourceReadingAssembler.assemble(
            "ラブ・ラブ",
            listOf(
                RawReadingToken(0, 2, "ラブ"),
                RawReadingToken(2, 3, "*"),
                RawReadingToken(3, 5, "ラブ"),
            ),
        )
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("라부 라부", result.text)
    }
    @Test fun anUnsupportedLargeTokenRetriesSmallerDictionarySplitBeforeDroppingLine() {
        var calls = 0
        val result = SourceReadingAssembler.retryModes("半端なく", 3) { mode ->
            calls++
            when (mode) {
                0 -> listOf(RawReadingToken(0, 5, "*"))
                else -> listOf(
                    RawReadingToken(0, 2, "ハンパ"),
                    RawReadingToken(2, 4, "ナク"),
                )
            }
        }
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("한파나쿠", result.text)
        assertEquals(2, calls)
    }

    @Test fun everyUnsupportedDictionarySplitStillFailsClosed() {
        val result = SourceReadingAssembler.retryModes("未知語", 3) {
            listOf(RawReadingToken(0, 3, "*"))
        }
        assertNull(result.text)
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, result.status)
    }
    @Test fun repeatedJapaneseWordsKeepOriginalSourceOffsets() {
        val reading = SourceReadingAssembler.assemble(
            "駄目駄目",
            listOf(
                RawReadingToken(0, 2, "ダメ"),
                RawReadingToken(2, 4, "ダメ"),
            ),
        )
        assertEquals(LineReadingStatus.COMPLETE, reading.status)
        assertEquals("다메다메", reading.text)
        assertEquals(listOf(0 to 2, 2 to 4), reading.spans.map { it.sourceStart to it.sourceEnd })
        assertEquals(listOf("駄目", "駄目"), reading.spans.map { it.sourceText })
    }

    @Test fun particleReadingDependsOnWholeLineMorphology() {
        val source = "母は花"
        val result = SourceReadingAssembler.assemble(
            source,
            listOf(
                RawReadingToken(0, 1, "ハハ"),
                RawReadingToken(1, 2, "ハ", isParticle = true),
                RawReadingToken(2, 3, "ハナ"),
            ),
        )
        assertEquals("하하와하나", result.text)
        assertEquals(listOf("하하", "와", "하나"), result.spans.map { it.hangul })
    }

    @Test fun originalSpacesAndEnglishArePreservedWithoutGuessingOtherWords() {
        val result = SourceReadingAssembler.assemble(
            "駄目 I love you!",
            listOf(
                RawReadingToken(0, 2, "ダメ"),
                RawReadingToken(3, 4, "I"),
                RawReadingToken(5, 9, "love"),
                RawReadingToken(10, 13, "you"),
                RawReadingToken(13, 14, "!"),
            ),
        )
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("다메 아이 러브 유", result.text)
        assertEquals("駄目", result.spans.first().sourceText)
        assertEquals("you", result.spans.last().sourceText)
    }

    @Test fun explicitWhitespaceAndPunctuationTokensKeepPhraseBoundaries() {
        val withSpace = SourceReadingAssembler.assemble(
            "駄目 I", listOf(
                RawReadingToken(0, 2, "ダメ"),
                RawReadingToken(2, 3, " "),
                RawReadingToken(3, 4, "I"),
            ),
        )
        assertEquals("다메 아이", withSpace.text)

        val withPunctuation = SourceReadingAssembler.assemble(
            "駄目、I", listOf(
                RawReadingToken(0, 2, "ダメ"),
                RawReadingToken(2, 3, "、"),
                RawReadingToken(3, 4, "I"),
            ),
        )
        assertEquals("다메 아이", withPunctuation.text)
    }

    @Test fun unknownKanjiAndUnknownEnglishNeverGeneratePlausibleFiller() {
        val missingKanji = SourceReadingAssembler.assemble("未知語", listOf(RawReadingToken(0, 3, "*")))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, missingKanji.status)
        assertNull(missingKanji.text)
        val missingLatin = SourceReadingAssembler.assemble("xyz", listOf(RawReadingToken(0, 3, "xyz")))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, missingLatin.status)
    }

    @Test fun invalidOverlappingOffsetsFailClosedInsteadOfRepeatingSyllables() {
        val invalid = SourceReadingAssembler.assemble(
            "脳みそ",
            listOf(RawReadingToken(0, 2, "ノウ"), RawReadingToken(1, 3, "ミソ")),
        )
        assertNull(invalid.text)
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, invalid.status)
    }

    @Test fun kanaAndEmojiNeverBecomeUnrelatedHangul() {
        val result = SourceReadingAssembler.assemble(
            "きゃっ！😊",
            listOf(RawReadingToken(0, 3, "キャッ"), RawReadingToken(3, 4, "！")),
        )
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("캿", result.text)
        assertTrue(result.spans.all { it.sourceText == "きゃっ" })
    }
}
