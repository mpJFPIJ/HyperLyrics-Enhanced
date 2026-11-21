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
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(
            1_100L, listOf(units),
        ))
        assertEquals(null, AppleTimedPronunciationHighlightPolicy.activeVariant(
            1_400L, listOf(units),
        ))
        assertEquals(0, AppleTimedPronunciationHighlightPolicy.activeVariant(
            1_700L, listOf(units),
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

}
