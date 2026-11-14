package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleTimedPronunciationHighlightPolicyTest {
    @Test
    fun `hangul syllables highlight individually using real native word timings`() {
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
        assertEquals(listOf("다", "메", "노"), segments.map { "다메 노".substring(it.start, it.end) })
        assertEquals(listOf(1000L, 1400L, 2000L), segments.map { it.beginMs })
        assertEquals(listOf(1400L, 1800L, 2400L), segments.map { it.endMs })
        assertEquals(1f, AppleTimedPronunciationHighlightPolicy.progress(1600L, segments[0]), 0.001f)
        assertEquals(0.5f, AppleTimedPronunciationHighlightPolicy.progress(1600L, segments[1]), 0.001f)
        assertEquals(0f, AppleTimedPronunciationHighlightPolicy.progress(1600L, segments[2]), 0.001f)
        // Seeking backward must dim the syllables again.
        assertEquals(0f, AppleTimedPronunciationHighlightPolicy.progress(1100L, segments[1]), 0.001f)
    }

    @Test
    fun `spans crossing multiple Apple karaoke cells keep syllables on the same clock`() {
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
        assertEquals(6, segments.size)
        assertTrue(segments.zipWithNext().all { (a, b) -> a.beginMs <= b.beginMs })
        assertTrue(segments.take(3).all { it.beginMs >= 1000 && it.endMs <= 1600 })
        assertTrue(segments.drop(3).all { it.beginMs >= 1900 && it.endMs <= 2500 })
    }

    @Test
    fun `missing token spans still show a timed progressive sweep and no whole line flash`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "みその中から", pronunciation = "미소노 나카카라",
            timedWords = listOf(
                AppleTimedSourceWord("みそ", 500, 900),
                AppleTimedSourceWord("の", 1000, 1150),
                AppleTimedSourceWord("中から", 1200, 2000),
            ),
        )
        assertEquals("미소노나카카라".length, units.size)
        assertTrue(units.zipWithNext().any { (a, b) -> a.beginMs != b.beginMs })
        assertTrue(units.any { AppleTimedPronunciationHighlightPolicy.progress(1050L, it) == 0f })
        assertTrue(units.any { AppleTimedPronunciationHighlightPolicy.progress(1050L, it) > 0f })
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
    fun `a genuine line begin and end can drive approximate syllable sweep without word data`() {
        val derivedLineWindow = AppleTimedSourceWord("だめ", 1000, 1800)
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "だめ", pronunciation = "다메", timedWords = listOf(derivedLineWindow),
        )
        assertEquals(listOf(1000L, 1400L), units.map { it.beginMs })
        assertEquals(listOf(1400L, 1800L), units.map { it.endMs })
    }

    @Test
    fun `unverified source token text does not impersonate an authoritative alignment`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "みその中から", pronunciation = "미소노 나카카라",
            timedWords = listOf(AppleTimedSourceWord("みその中から", 1000, 2000)),
            phoneticSpans = listOf(ApplePronunciationSourceToken(0, 2, "바보")),
        )
        assertEquals("미소노나카카라".length, units.size)
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
    fun `partial Apple word times keep real anchors and animate missing syllables`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "ABC", pronunciation = "가나다",
            timedWords = listOf(
                AppleTimedSourceWord("A", 100, 200),
                AppleTimedSourceWord("C", 700, 800),
            ),
            lineBeginMs = 50, lineEndMs = 850,
        )
        assertEquals(3, units.size)
        assertEquals(listOf(100L, 200L, 700L), units.map { it.beginMs })
        assertEquals(listOf(200L, 700L, 800L), units.map { it.endMs })
        assertEquals(0.5f, AppleTimedPronunciationHighlightPolicy.progress(450, units[1]), 0.001f)
        assertEquals(0f, AppleTimedPronunciationHighlightPolicy.progress(450, units[2]), 0.001f)
    }

    @Test
    fun `real native line window animates when word objects have no timestamps`() {
        val units = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = "ありがとう", pronunciation = "아리가토",
            timedWords = emptyList(), lineBeginMs = 1000, lineEndMs = 1800,
        )
        assertEquals(4, units.size)
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

}
