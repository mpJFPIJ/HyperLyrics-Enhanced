package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReadingSpanTest {
    @Test fun verifiedKanjiSyllableBoundariesAreAvailableEvenBeforeTheHostMeasuresItsWrap() {
        val source = "世界"
        val coarse = SourceReadingAssembler.assemble(
            source, listOf(RawReadingToken(0, 2, "セカイ")),
        )
        val refined = SourceReadingAssembler.refinePotentialVisualLineBoundaries(
            source, coarse,
        ) { fragment ->
            val reading = when (fragment) {
                "世" -> "セ"
                "界" -> "カイ"
                else -> error("Only authentic source characters may be analyzed")
            }
            SourceReadingAssembler.assemble(fragment, listOf(RawReadingToken(0, 1, reading)))
        }
        assertEquals("세카이", refined.text)
        assertEquals(listOf("세", "카이"), refined.spans.map { it.hangul })
        assertEquals(listOf(0 to 1, 1 to 2),
            refined.spans.map { it.sourceStart to it.sourceEnd })
    }

    @Test fun unprovedSingleKanjiReadingsNeverSplitAContextualCompound() {
        val source = "今日"
        val coarse = SourceReadingAssembler.assemble(
            source, listOf(RawReadingToken(0, 2, "キョウ")),
        )
        val refined = SourceReadingAssembler.refinePotentialVisualLineBoundaries(
            source, coarse,
        ) { fragment ->
            val reading = if (fragment == "今") "イマ" else "ヒ"
            SourceReadingAssembler.assemble(fragment, listOf(RawReadingToken(0, 1, reading)))
        }
        assertEquals(coarse, refined)
    }

    @Test fun isolatedNativeKanjiDictionaryReadingsRefineOnlyAnExactlyRejoinedWholeLineSound() {
        val source = "駄目は"
        val wholeLine = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 2, "ダメ"),
            RawReadingToken(2, 3, "ハ", isParticle = true),
        ))
        val refined = SourceReadingAssembler.refineCoarseNativeWords(
            source, listOf("駄", "目", "は"), wholeLine,
        ) { cell ->
            when (cell) {
                "駄" -> SourceReadingAssembler.assemble(cell, listOf(RawReadingToken(0, 1, "ダ")))
                "目" -> SourceReadingAssembler.assemble(cell, listOf(RawReadingToken(0, 1, "メ")))
                else -> error("The original particle is already independently timed")
            }
        }
        assertEquals("다메와", refined.text)
        assertEquals(listOf("다", "메", "와"), refined.spans.map { it.hangul })
        assertEquals(listOf("駄", "目", "は"), refined.spans.map { it.sourceText })
    }

    @Test fun independentlyReadableKanjiThatChangesFullLineSoundNeverGetsFakeWordTiming() {
        val source = "駄目"
        val coarse = SourceReadingAssembler.assemble(source, listOf(RawReadingToken(0, 2, "ダメ")))
        val refined = SourceReadingAssembler.refineCoarseNativeWords(
            source, listOf("駄", "目"), coarse,
        ) { cell ->
            val reading = if (cell == "駄") "ダ" else "モク"
            SourceReadingAssembler.assemble(cell, listOf(RawReadingToken(0, 1, reading)))
        }
        assertEquals(coarse, refined)
    }

    @Test fun finerWholeLineMorphologyRecoversFourNativeWordClocksWithoutGuessingKanjiSounds() {
        val result = SourceReadingAssembler.retryModesForNativeWords(
            "駄目駄目", listOf("駄", "目", "駄", "目"), modeCount = 3,
        ) { mode ->
            when (mode) {
                0 -> listOf(RawReadingToken(0, 2, "ダメ"), RawReadingToken(2, 4, "ダメ"))
                1 -> listOf(
                    RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"),
                    RawReadingToken(2, 3, "ダ"), RawReadingToken(3, 4, "メ"),
                )
                else -> listOf(RawReadingToken(0, 4, "ダメダメ"))
            }
        }
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("다메다메", result.text)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
            result.spans.map { it.sourceStart to it.sourceEnd })
        assertEquals(listOf("다", "메", "다", "메"), result.spans.map { it.hangul })
    }

    @Test fun finerTokenizationCannotChangeWholeSentenceOrParticlePronunciation() {
        val result = SourceReadingAssembler.retryModesForNativeWords(
            "駄目は", listOf("駄", "目", "は"), modeCount = 3,
        ) { mode ->
            when (mode) {
                0 -> listOf(RawReadingToken(0, 2, "ダメ"),
                    RawReadingToken(2, 3, "ハ", isParticle = true))
                1 -> listOf(RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"),
                    RawReadingToken(2, 3, "ハ", isParticle = true))
                else -> listOf(RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"),
                    RawReadingToken(2, 3, "ハ", isParticle = false))
            }
        }
        assertEquals("다메와", result.text)
        assertEquals(listOf("다", "메", "와"), result.spans.map { it.hangul })
    }

    @Test fun incompleteOrMisorderedAppleCellsDoNotPromoteUnprovedFinerSourceOffsets() {
        val source = "駄目駄目"
        val result = SourceReadingAssembler.retryModesForNativeWords(
            source, listOf("駄", "目"), modeCount = 2,
        ) { mode ->
            if (mode == 0) listOf(RawReadingToken(0, 2, "ダメ"), RawReadingToken(2, 4, "ダメ"))
            else listOf(RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"),
                RawReadingToken(2, 3, "ダ"), RawReadingToken(3, 4, "メ"))
        }
        assertEquals("다메다메", result.text)
        assertEquals(listOf("駄目", "駄目"), result.spans.map { it.sourceText })
    }

    @Test fun alreadyExactNativeWordBoundaryMapDoesNotRetokenizeForNoBenefit() {
        var analyses = 0
        val result = SourceReadingAssembler.retryModesForNativeWords(
            "駄目", listOf("駄", "目"), modeCount = 3,
        ) {
            analyses++
            listOf(RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"))
        }
        assertEquals("다메", result.text)
        assertEquals(1, analyses)
    }

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

    @Test fun `failed later morphology passes cannot discard a previously verified source anchor`() {
        val result = SourceReadingAssembler.retryModes("花未知", 3) { mode ->
            when (mode) {
                0 -> listOf(RawReadingToken(0, 1, "ハナ"), RawReadingToken(1, 3, "*"))
                1 -> listOf(RawReadingToken(0, 3, "*"))
                else -> emptyList()
            }
        }
        assertNull(result.text)
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, result.status)
        assertEquals(listOf("花"), result.spans.map { it.sourceText })
        assertEquals(listOf("하나"), result.spans.map { it.hangul })
    }

    @Test fun `equal verified coverage prefers the first Sudachi mode`() {
        val result = SourceReadingAssembler.retryModes("花未知", 2) { mode ->
            listOf(RawReadingToken(0, 1, if (mode == 0) "ハナ" else "カ"),
                RawReadingToken(1, 3, "*"))
        }
        assertEquals("하나", result.spans.single().hangul)
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
