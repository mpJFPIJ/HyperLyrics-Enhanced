package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleTimedPronunciationHighlightPolicyTest {
    @Test fun noVerifiedSourcePhoneticsMeansOneHonestLineWindowNotGuessedSyllables() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "溺れ",
            pronunciation = "오보레",
            timedWords = listOf(AppleTimedSourceWord("溺れ", 1_000L, 1_800L)),
        )
        assertEquals(1, units.size)
        assertEquals(0, units.single().start)
        assertEquals("오보레".length, units.single().end)
        assertEquals(-1, units.single().sourceWordIndex)
        assertEquals(1_000L, units.single().beginMs)
        assertEquals(1_800L, units.single().endMs)
    }

    @Test fun verifiedWholeMorphemeCrossingNativeCellsRetainsItsOneSoundGroup() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "溺れ",
            pronunciation = "오보레",
            timedWords = listOf(
                AppleTimedSourceWord("溺", 1_000L, 1_400L),
                AppleTimedSourceWord("れ", 1_400L, 1_800L),
            ),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 2, "오보레", "溺れ")),
        )
        assertEquals(1, units.size)
        assertEquals("오보레", "오보레".substring(units.single().start, units.single().end))
        assertEquals(1_000L, units.single().beginMs)
        assertEquals(1_800L, units.single().endMs)
        assertEquals(-1, units.single().sourceWordIndex)
    }

    @Test fun `verified kanji kana native words keep their individual clocks through a real rest`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "脳みそ", pronunciation = "노미소",
            timedWords = listOf(
                AppleTimedSourceWord("脳", 1000L, 1300L),
                AppleTimedSourceWord("みそ", 1500L, 1900L),
            ),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 3, "노미소", "脳みそ")),
        )
        assertEquals(listOf("노", "미소"), units.map { "노미소".substring(it.start, it.end) })
        assertEquals(listOf(1000L, 1500L), units.map { it.beginMs })
        assertEquals(listOf(1300L, 1900L), units.map { it.endMs })
        assertEquals(listOf(0, 1), units.map { it.sourceWordIndex })
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.speakingVariant(
            1400L, listOf(units),
        ))
    }

    @Test fun `two unknown kanji native fragments never inherit invented split clocks`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "世界", pronunciation = "세카이",
            timedWords = listOf(
                AppleTimedSourceWord("世", 1000L, 1300L),
                AppleTimedSourceWord("界", 1500L, 1900L),
            ),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 2, "세카이", "世界")),
        )
        assertEquals(1, units.size)
        assertTrue(units.single().isWholeLine)
    }

    @Test fun `nonmonotonic native word clocks cannot be split at a single apparent rest`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "脳みそよ", pronunciation = "노미소요",
            timedWords = listOf(
                AppleTimedSourceWord("脳", 1000L, 1300L),
                AppleTimedSourceWord("みそ", 1500L, 1900L),
                AppleTimedSourceWord("よ", 1600L, 2000L),
            ),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 4, "노미소요", "脳みそよ")),
        )
        assertEquals(1, units.size)
        assertTrue(units.single().isWholeLine)
    }

    @Test fun `no phonetic map only reveals full line at real line bounds`() {
        val timed = listOf(
            AppleTimedSourceWord("みその中", 1_000L, 2_000L),
            AppleTimedSourceWord("から", 2_000L, 2_400L),
        )
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "みその中から",
            pronunciation = "미소노나카카라",
            timedWords = timed,
        )
        assertEquals(1, units.size)
        assertEquals("미소노나카카라".length, units.single().end)
        assertEquals(-1, units.single().sourceWordIndex)
        assertEquals(1_000L, units.single().beginMs)
        assertEquals(2_400L, units.single().endMs)
        assertEquals(1f, AppleTimedPronunciationHighlightPolicy.progress(
            2_400L, units.single()), 0.001f)
    }

    @Test
    fun `non-playing previous and future karaoke rows never remain highlighted`() {
        val a = listOf(AppleTimedReadingUnit(0, 1, 1_000L, 1_500L))
        val b = listOf(AppleTimedReadingUnit(0, 1, 7_000L, 7_500L))
        val variants = listOf(a, b)
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(999L, variants))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(1_100L, variants))
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(1_500L, variants))
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(5_000L, variants))
        assertEquals(1, AppleTimedPronunciationHighlightPolicy.activeVariant(7_100L, variants))
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(10_000L, variants))
    }

    @Test
    fun `gap between trusted native word clocks is not itself a playing interval`() {
        val units = listOf(
            AppleTimedReadingUnit(0, 1, 1_000L, 1_200L),
            AppleTimedReadingUnit(2, 3, 1_600L, 1_800L),
        )
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.speakingVariant(
            1_100L, listOf(units),
        ))
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.speakingVariant(
            1_400L, listOf(units),
        ))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.speakingVariant(
            1_700L, listOf(units),
        ))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(
            1_400L, listOf(units),
        ))
    }

    @Test
    fun `current lyric keeps completed Hangul word bright through a native inter-word rest`() {
        val chorus = listOf(
            AppleTimedReadingUnit(0, 2, 1_000L, 1_200L),
            AppleTimedReadingUnit(3, 5, 1_600L, 1_800L),
        )
        val nextChorus = listOf(
            AppleTimedReadingUnit(0, 2, 9_000L, 9_200L),
            AppleTimedReadingUnit(3, 5, 9_600L, 9_800L),
        )
        val variants = listOf(chorus, nextChorus)
        // Apple keeps already sung words bright while waiting for the next
        // word in the same lyric. The old implementation reset everything
        // to grey as soon as no word was *currently* being sung.
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(
            1_400L, variants,
        ))
        assertEquals(1f, AppleTimedPronunciationHighlightPolicy.progress(
            1_400L, chorus[0],
        ), 0f)
        assertEquals(0f, AppleTimedPronunciationHighlightPolicy.progress(
            1_400L, chorus[1],
        ), 0f)
        // This must not activate a prior chorus during the next verse.
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(
            5_000L, variants,
        ))
        assertEquals(1, AppleTimedPronunciationHighlightPolicy.activeVariant(
            9_400L, variants,
        ))
    }

    @Test
    fun `verified phonetic morphemes keep their exact original Apple word windows`() {
        val segments = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "駄目脳",
            pronunciation = "다메 노",
            timedWords = listOf(
                AppleTimedSourceWord("駄目", 1000, 1800),
                AppleTimedSourceWord("脳", 2000, 2400),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 2, "다메"),
                ApplePronunciationSourceToken(2, 3, "노"),
            ),
        )
        assertEquals(listOf("다메", "노"), segments.map { "다메 노".substring(it.start, it.end) })
        assertEquals(listOf(1000L, 2000L), segments.map { it.beginMs })
        assertEquals(listOf(1800L, 2400L), segments.map { it.endMs })
        assertEquals(0.75f, AppleTimedPronunciationHighlightPolicy.progress(1600L, segments[0]), 0.001f)
        assertEquals(0f, AppleTimedPronunciationHighlightPolicy.progress(1600L, segments[1]), 0.001f)
        // Seeking backward must rewind the *whole corresponding sound group*.
        assertEquals(0.125f, AppleTimedPronunciationHighlightPolicy.progress(1100L, segments[0]), 0.001f)
    }

    @Test
    fun `spans crossing multiple Apple karaoke cells keep each sound group intact`() {
        val segments = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "ダーリンベイビー",
            pronunciation = "다아린 베이비",
            timedWords = listOf(
                AppleTimedSourceWord("ダー", 1000, 1300),
                AppleTimedSourceWord("リン", 1300, 1600),
                AppleTimedSourceWord("ベイビー", 1900, 2500),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 4, "다아린"),
                ApplePronunciationSourceToken(4, 8, "베이비"),
            ),
        )
        assertEquals(2, segments.size)
        assertEquals(listOf("다아린", "베이비"),
            segments.map { "다아린 베이비".substring(it.start, it.end) })
        assertTrue(segments.zipWithNext().all { (a, b) -> a.beginMs <= b.beginMs })
        assertEquals(listOf(1000L, 1900L), segments.map { it.beginMs })
        assertEquals(listOf(1600L, 2500L), segments.map { it.endMs })
    }

    @Test
    fun `missing token spans use one documented native whole line window`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "みその中から", pronunciation = "미소노 나카카라",
            timedWords = listOf(
                AppleTimedSourceWord("みそ", 500, 900),
                AppleTimedSourceWord("の", 1000, 1150),
                AppleTimedSourceWord("中から", 1200, 2000),
            ),
        )
        assertEquals(1, units.size)
        assertEquals(500L, units.single().beginMs)
        assertEquals(2000L, units.single().endMs)
        assertEquals(-1, units.single().sourceWordIndex)
    }

    @Test
    fun `invalid native timings must not invent one giant all bright unit`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "駄目", pronunciation = "다메",
            timedWords = listOf(AppleTimedSourceWord("駄目", 800, 800)),
        )
        assertTrue(units.isEmpty())
    }

    @Test
    fun `repeated chorus selects timing for the occurrence actually playing`() {
        val earlier = listOf(AppleTimedReadingUnit(0, 1, 1000, 1500))
        val later = listOf(AppleTimedReadingUnit(0, 1, 8000, 8500))
        val variants = listOf(later, earlier)
        assertEquals(1, AppleTimedPronunciationHighlightPolicy.closestVariant(1300, variants))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.closestVariant(8200, variants))
    }

    @Test
    fun `a genuine line begin and end never invent per syllable boundaries`() {
        val derivedLineWindow = AppleTimedSourceWord("だめ", 1000, 1800)
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "だめ", pronunciation = "다메", timedWords = listOf(derivedLineWindow),
        )
        assertEquals(1, units.size)
        assertEquals(1000L, units.single().beginMs)
        assertEquals(1800L, units.single().endMs)
    }

    @Test
    fun `unverified source token text does not impersonate an authoritative alignment`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "みその中から", pronunciation = "미소노 나카카라",
            timedWords = listOf(AppleTimedSourceWord("みその中から", 1000, 2000)),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 2, "바보")),
        )
        assertEquals(1, units.size)
        assertFalse(units.any { it.start >= it.end })
    }

    @Test
    fun `missing timing for one word never gives its syllable another words timestamps`() {
        for (untimedIndex in 0..2) {
            val source = "ABC"
            val original = listOf(
                AppleTimedSourceWord("A", 100, 200),
                AppleTimedSourceWord("B", 300, 400),
                AppleTimedSourceWord("C", 500, 600),
            )
            val words = original.mapIndexed { index, word ->
                if (index == untimedIndex) word.copy(endMs = word.beginMs) else word
            }
            assertTrue(AppleTimedPronunciationHighlightPolicy.units(
                source, "가나다", words,
            ).isEmpty())
            val verified = AppleTimedPronunciationHighlightPolicy.units(
                source, "가나다", words,
                phoneticSpans = listOf(
                    ApplePronunciationSourceToken(0, 1, "가", "A"),
                    ApplePronunciationSourceToken(1, 2, "나", "B"),
                    ApplePronunciationSourceToken(2, 3, "다", "C"),
                ),
            )
            assertEquals(2, verified.size)
            assertFalse(verified.any { it.start == untimedIndex })
        }
    }
    @Test
    fun `partial native word time plus real line bounds cannot invent missing syllable clocks`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "ABC", pronunciation = "가나다",
            timedWords = listOf(
                AppleTimedSourceWord("A", 100, 200),
                AppleTimedSourceWord("C", 700, 800),
            ),
            lineBeginMs = 50, lineEndMs = 850,
        )
        assertEquals(1, units.size)
        assertEquals(50L, units.single().beginMs)
        assertEquals(850L, units.single().endMs)
        assertEquals(-1, units.single().sourceWordIndex)
        assertEquals(0.5f, AppleTimedPronunciationHighlightPolicy.progress(450, units.single()), 0.001f)
    }

    @Test
    fun `real native line window animates when word objects have no timestamps`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "ありがとう", pronunciation = "아리가토",
            timedWords = emptyList(), lineBeginMs = 1000, lineEndMs = 1800,
        )
        assertEquals(1, units.size)
        assertTrue(units.all { it.endMs > it.beginMs })
        assertEquals(1000L, units.first().beginMs)
        assertEquals(1800L, units.last().endMs)
    }

    @Test
    fun `invalid line bounds cannot be mistaken for verified karaoke timing`() {
        assertTrue(AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "だめ", pronunciation = "다메",
            timedWords = emptyList(), lineBeginMs = 900, lineEndMs = 800,
        ).isEmpty())
    }


    @Test
    fun `partialVerifiedReadingSpansKeepTheirOwnNativeWordClocks`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "A中CDE",
            pronunciation = "가나다",
            timedWords = listOf(
                AppleTimedSourceWord("A", 100, 200),
                AppleTimedSourceWord("中", 300, 400),
                AppleTimedSourceWord("CDE", 700, 1000),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 1, "가", "A"),
                ApplePronunciationSourceToken(2, 5, "다", "CDE"),
            ),
        )
        assertEquals(listOf("가", "다"), units.map { "가나다".substring(it.start, it.end) })
        assertEquals(listOf(100L, 700L), units.map { it.beginMs })
        assertEquals(listOf(200L, 1000L), units.map { it.endMs })
        assertEquals(listOf(0, 2), units.map { it.sourceWordIndex })
    }

    @Test
    fun `unknownReadingBetweenVerifiedAnchorsRemainsUnanimatedEvenWithLineWindow`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "A中CDE",
            pronunciation = "가나다",
            timedWords = listOf(
                AppleTimedSourceWord("A", 100, 200),
                AppleTimedSourceWord("中", 300, 400),
                AppleTimedSourceWord("CDE", 700, 1000),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 1, "가", "A"),
                ApplePronunciationSourceToken(2, 5, "다", "CDE"),
            ),
            lineBeginMs = 50,
            lineEndMs = 1100,
        )
        assertEquals(2, units.size)
        assertEquals(listOf("가", "다"), units.map { "가나다".substring(it.start, it.end) })
        assertEquals(listOf(100L, 700L), units.map { it.beginMs })
        assertEquals(listOf(200L, 1000L), units.map { it.endMs })
        assertEquals(listOf(0, 2), units.map { it.sourceWordIndex })
    }

    @Test fun invalidClockStillConsumesItsRepeatedNativeWord() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "かなかな", pronunciation = "카나카나",
            timedWords = listOf(
                AppleTimedSourceWord("かな", -1, -1),
                AppleTimedSourceWord("かな", 700, 900),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 2, "카나", "かな"),
                ApplePronunciationSourceToken(2, 4, "카나", "かな"),
            ),
        )
        assertEquals(listOf(AppleTimedReadingUnit(2, 4, 700, 900, 1)), units)
    }

    @Test fun missingRepeatedCellWithoutAnOffsetCannotTimeEitherOccurrence() {
        assertTrue(AppleTimedPronunciationHighlightPolicy.units(
            "かなかな", "카나카나", listOf(AppleTimedSourceWord("かな", 700, 900)),
            listOf(
                ApplePronunciationSourceToken(0, 2, "카나", "かな"),
                ApplePronunciationSourceToken(2, 4, "카나", "かな"),
            ),
        ).isEmpty())
    }

    @Test fun sharedBoundariesPreserveManyToOneAndOneToManyClocksTogether() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "あい今日", pronunciation = "아 이 쿄",
            timedWords = listOf(
                AppleTimedSourceWord("あい", 100, 300),
                AppleTimedSourceWord("今", 400, 500),
                AppleTimedSourceWord("日", 500, 800),
            ),
            phoneticSpans = listOf(
                ApplePronunciationSourceToken(0, 1, "아", "あ"),
                ApplePronunciationSourceToken(1, 2, "이", "い"),
                ApplePronunciationSourceToken(2, 4, "쿄", "今日"),
            ),
        )
        assertEquals(listOf(
            AppleTimedReadingUnit(0, 3, 100, 300, 0),
            AppleTimedReadingUnit(4, 5, 400, 800),
        ), units)
    }

    @Test fun sharedClockNeverBridgesAnUntimedCellOrARealPause() {
        val sounds = listOf(
            ApplePronunciationSourceToken(0, 2, "아시타", "明日"),
            ApplePronunciationSourceToken(2, 3, "우", "う"),
        )
        for (middle in listOf(
            AppleTimedSourceWord("日", -1, -1),
            AppleTimedSourceWord("日", 500, 600),
        )) {
            val units = AppleTimedPronunciationHighlightPolicy.units(
                "明日う", "아시타 우", listOf(
                    AppleTimedSourceWord("明", 100, 200),
                    middle,
                    AppleTimedSourceWord("う", 700, 900),
                ), sounds,
            )
            assertEquals(listOf(AppleTimedReadingUnit(4, 5, 700, 900, 2)), units)
        }
    }

    @Test fun literalKanaKeepsVerifiedNativeClocksAcrossVisualRowsWithoutStoredSpans() {
        val source = "かなかな"
        val reading = "카나 카나"
        val words = listOf(AppleTimedSourceWord("かな", 100, 250),
            AppleTimedSourceWord("かな", 400, 550))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(source, reading, listOf(
            AppleVisualSourceCell("かな", 0, 20), AppleVisualSourceCell("かな", 25, 45),
        ), emptyList()))
        val units = AppleTimedPronunciationHighlightPolicy.units(source, reading, words)
        assertEquals(listOf(
            AppleTimedReadingUnit(0, 2, 100, 250, 0),
            AppleTimedReadingUnit(3, 5, 400, 550, 1),
        ), units)
        assertEquals(listOf(AppleTimedReadingUnit(0, 2, 400, 550, 1)),
            AppleInterleavedReadingTimingPolicy.project(listOf(units), reading, rows, rows[1]).single())
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.speakingVariant(300, listOf(units)))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(300, listOf(units)))
    }

    @Test fun literalKanaNeverInventsClocksAcrossNonAdditiveSourceBoundaries() {
        for ((parts, reading) in listOf(
            listOf("き", "ゃ") to "캬",
            listOf("か", "ん") to "칸",
            listOf("そ", "う") to "소",
            listOf("か", "な") to "카너",
        )) {
            val words = parts.mapIndexed { index, part ->
                AppleTimedSourceWord(part, 100L * index, 100L * index + 100)
            }
            val units = AppleTimedPronunciationHighlightPolicy.units(
                parts.joinToString(""), reading, words,
            )
            assertTrue(units.single().isWholeLine)
        }
    }

    @Test fun storedLiteralKanaSpanCanKeepTwoSeparateNativeWordClocks() {
        val words = listOf(AppleTimedSourceWord("かな", 100, 200),
            AppleTimedSourceWord("かな", 300, 400))
        val spans = listOf(ApplePronunciationSourceToken(0, 4, "카나카나", "かなかな"))
        val source = "かなかな"
        val reading = "카나카나"
        val units = AppleTimedPronunciationHighlightPolicy.units(source, reading, words, spans)
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(source, reading, listOf(
            AppleVisualSourceCell("かな", 0, 20), AppleVisualSourceCell("かな", 25, 45),
        ), spans))
        assertEquals(listOf(AppleTimedReadingUnit(0, 2, 100, 200, 0)),
            AppleInterleavedReadingTimingPolicy.project(listOf(units), reading, rows, rows[0]).single())
        assertEquals(listOf(AppleTimedReadingUnit(0, 2, 300, 400, 1)),
            AppleInterleavedReadingTimingPolicy.project(listOf(units), reading, rows, rows[1]).single())
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.speakingVariant(250, listOf(units)))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(250, listOf(units)))
    }

    @Test fun storedDigraphStillUsesOnlyItsIntactWindow() {
        val units = AppleTimedPronunciationHighlightPolicy.units("きゃ", "캬", listOf(
            AppleTimedSourceWord("き", 100, 200), AppleTimedSourceWord("ゃ", 200, 350),
        ), listOf(ApplePronunciationSourceToken(0, 2, "캬", "きゃ")))
        assertEquals(listOf(AppleTimedReadingUnit(0, 1, 100, 350)), units)
    }
}
