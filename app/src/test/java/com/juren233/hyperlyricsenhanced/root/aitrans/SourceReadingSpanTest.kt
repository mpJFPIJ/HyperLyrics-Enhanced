package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReadingSpanTest {
    @Test fun omittedOrDetachedJapaneseVoicingMarkCannotBecomeACompleteDictionaryReading() {
        for (source in listOf("か\u3099", "か\u309a", "か\u309b", "か\u309c", "ｶﾞ", "ﾊﾟ")) {
            val missingMark = SourceReadingAssembler.assemble(source, listOf(
                RawReadingToken(0, 1, "カ"),
            ))
            assertEquals("Missing mark in $source", LineReadingStatus.MISSING_UNSUPPORTED, missingMark.status)
            assertNull(missingMark.text)

            val detachedMark = SourceReadingAssembler.assemble(source, listOf(
                RawReadingToken(0, 1, "カ"),
                RawReadingToken(1, 2, "ア"),
            ))
            assertEquals("Detached mark in $source", LineReadingStatus.MISSING_UNSUPPORTED, detachedMark.status)
        }

        val voiced = SourceReadingAssembler.assemble("か\u3099愛", listOf(
            RawReadingToken(0, 2, "ガ"), RawReadingToken(2, 3, "アイ"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, voiced.status)
        assertEquals("가아이", voiced.text)
        assertEquals(listOf("か\u3099", "愛"), voiced.spans.map { it.sourceText })
        assertEquals(listOf(0 to 2, 2 to 3),
            voiced.spans.map { it.sourceStart to it.sourceEnd })

        for (mark in listOf("\u3099", "\u309a", "\u309b", "\u309c", "ﾞ", "ﾟ")) {
            val invented = SourceReadingAssembler.assemble(mark, listOf(
                RawReadingToken(0, mark.length, "ガ"),
            ))
            assertEquals("Orphan mark $mark cannot sound", LineReadingStatus.MISSING_UNSUPPORTED, invented.status)
        }
    }

    @Test fun nativeWordRefinementMustNotSkipOrBisectAJapaneseVoicingMark() {
        val source = "か\u3099愛"
        val coarse = SourceReadingAssembler.assemble(source, listOf(RawReadingToken(0, 3, "ガアイ")))
        assertEquals(LineReadingStatus.COMPLETE, coarse.status)
        val refined = SourceReadingAssembler.refineCoarseNativeWords(
            source, listOf("か", "愛"), coarse,
        ) { cell ->
            SourceReadingAssembler.assemble(cell, listOf(RawReadingToken(0, cell.length, "アイ")))
        }
        assertEquals(coarse, refined)

        val candidate = SourceReadingAssembler.retryModesForNativeWords(
            source, listOf("か", "愛"), modeCount = 2,
        ) { mode ->
            if (mode == 0) listOf(RawReadingToken(0, 3, "ガアイ"))
            else listOf(RawReadingToken(0, 2, "ガ"), RawReadingToken(2, 3, "アイ"))
        }
        // Apple's split か | 愛 omits the voicing mark; it cannot authorize
        // independent clock boundaries even though dictionary tokens are sound.
        assertEquals(listOf(0 to 3), candidate.spans.map { it.sourceStart to it.sourceEnd })

        val intactNativeWords = SourceReadingAssembler.retryModesForNativeWords(
            source, listOf("か\u3099", "愛"), modeCount = 2,
        ) { mode ->
            if (mode == 0) listOf(RawReadingToken(0, 3, "ガアイ"))
            else listOf(RawReadingToken(0, 2, "ガ"), RawReadingToken(2, 3, "アイ"))
        }
        assertEquals(listOf(0 to 2, 2 to 3),
            intactNativeWords.spans.map { it.sourceStart to it.sourceEnd })
    }

    @Test fun halfwidthDictionaryTokensKeepRawSourceOffsetsAndExtendStandaloneLengthMarks() {
        val source = "ｶﾞﾅｰ"
        val result = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 2, "ガ"),
            RawReadingToken(2, 3, "ﾅ"),
            RawReadingToken(3, 4, "ｰ"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("가나", result.text)
        assertEquals(listOf("ｶﾞ", "ﾅｰ"), result.spans.map { it.sourceText })
        assertEquals(listOf(0 to 2, 2 to 4),
            result.spans.map { it.sourceStart to it.sourceEnd })

        val unknownReading = SourceReadingAssembler.assemble("ｶﾞ", listOf(
            RawReadingToken(0, 2, "*"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, unknownReading.status)
        assertEquals("가", unknownReading.text)
        assertEquals("ｶﾞ", unknownReading.spans.single().sourceText)

        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED,
            SourceReadingAssembler.assemble("Iｰ", listOf(
                RawReadingToken(0, 1, "I"),
                RawReadingToken(1, 2, "ｰ"),
            )).status)
    }

    @Test fun standaloneLongVowelMarkExtendsThePreviousVerifiedJapaneseToken() {
        val katakana = SourceReadingAssembler.assemble("コー", listOf(
            RawReadingToken(0, 1, "コ"),
            RawReadingToken(1, 2, "ー"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, katakana.status)
        assertEquals("코", katakana.text)
        assertEquals(listOf("コー"), katakana.spans.map { it.sourceText })
        assertEquals(listOf(0 to 2), katakana.spans.map { it.sourceStart to it.sourceEnd })

        val supplementary = SourceReadingAssembler.assemble("𠮟ー", listOf(
            RawReadingToken(0, 2, "シツ"),
            RawReadingToken(2, 3, "ー"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, supplementary.status)
        assertEquals("시츠", supplementary.text)
        assertEquals(listOf("𠮟ー"), supplementary.spans.map { it.sourceText })
        assertEquals(listOf(0 to 3), supplementary.spans.map { it.sourceStart to it.sourceEnd })
        // An ASCII source token cannot claim a Japanese vowel mark merely
        // because it immediately precedes it in the native tokenizer.
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED,
            SourceReadingAssembler.assemble("Iー", listOf(
                RawReadingToken(0, 1, "I"),
                RawReadingToken(1, 2, "ー"),
            )).status)
    }

    @Test fun supplementaryHanIsPreservedAsOneJapaneseSourceToken() {
        val source = "𠮟る"
        val result = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 2, "シツ"), RawReadingToken(2, 3, "ル"),
        ))
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("시츠루", result.text)
        assertEquals(listOf("𠮟", "る"), result.spans.map { it.sourceText })
        assertEquals(listOf(0 to 2, 2 to 3),
            result.spans.map { it.sourceStart to it.sourceEnd })
    }

    @Test fun missingSupplementaryHanOrBisectedSurrogateCannotPassFullLineAnalysis() {
        val source = "𠮟る"
        val missing = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(2, 3, "ル"),
        ))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, missing.status)
        assertNull(missing.text)

        val trailing = SourceReadingAssembler.assemble("る𠮟", listOf(
            RawReadingToken(0, 1, "ル"),
        ))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, trailing.status)
        assertNull(trailing.text)

        val bisected = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 1, "シ"), RawReadingToken(1, 3, "ツル"),
        ))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, bisected.status)
        assertNull(bisected.text)
    }

    @Test fun supplementaryHanCanBeRefinedOnlyWhenNativeCellsRejoinTheWholeSound() {
        val source = "𠮟あ"
        val coarse = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 3, "シツア"),
        ))
        val refined = SourceReadingAssembler.refineCoarseNativeWords(
            source, listOf("𠮟", "あ"), coarse,
        ) { cell ->
            when (cell) {
                "𠮟" -> SourceReadingAssembler.assemble(cell, listOf(
                    RawReadingToken(0, 2, "シツ"),
                ))
                "あ" -> SourceReadingAssembler.assemble(cell, listOf(
                    RawReadingToken(0, 1, "ア"),
                ))
                else -> error("Unexpected native cell")
            }
        }
        assertEquals("시츠아", refined.text)
        assertEquals(listOf("𠮟", "あ"), refined.spans.map { it.sourceText })
        assertEquals(listOf(0 to 2, 2 to 3),
            refined.spans.map { it.sourceStart to it.sourceEnd })
    }

    @Test fun nativeWordOffsetsCannotSkipSupplementaryHanAndClaimFinerTiming() {
        val result = SourceReadingAssembler.retryModesForNativeWords(
            "𠮟あう", listOf("あ", "う"), modeCount = 2,
        ) { mode ->
            if (mode == 0) listOf(RawReadingToken(0, 4, "シツアウ"))
            else listOf(
                RawReadingToken(0, 2, "シツ"), RawReadingToken(2, 3, "ア"),
                RawReadingToken(3, 4, "ウ"),
            )
        }
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        // Incomplete Apple cells cannot select a different dictionary split.
        assertEquals(listOf("𠮟あう"), result.spans.map { it.sourceText })
    }

    @Test fun potentialVisualLineRefinementCountsUnicodeGlyphsNotSurrogateHalves() {
        val source = "𠮟あ"
        val coarse = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 3, "シツア"),
        ))
        val refined = SourceReadingAssembler.refinePotentialVisualLineBoundaries(
            source, coarse,
        ) { cell ->
            val sound = if (cell == "𠮟") "シツ" else "ア"
            SourceReadingAssembler.assemble(cell, listOf(RawReadingToken(0, cell.length, sound)))
        }
        assertEquals(listOf("시츠", "아"), refined.spans.map { it.hangul })
        assertEquals(listOf(0 to 2, 2 to 3),
            refined.spans.map { it.sourceStart to it.sourceEnd })
    }

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
