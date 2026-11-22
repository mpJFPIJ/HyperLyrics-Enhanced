package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleInterleavedReadingPolicyTest {
    private val source = "瞳孔バチ開いて溺れ死にそう"
    private val pronunciation = "도코 바치 히라이테 오보레 시니소"
    private val spans = listOf(
        ApplePronunciationSourceToken(0, 2, "도코", "瞳孔"),
        ApplePronunciationSourceToken(2, 4, "바치", "バチ"),
        ApplePronunciationSourceToken(4, 7, "히라이테", "開いて"),
        ApplePronunciationSourceToken(7, 9, "오보레", "溺れ"),
        ApplePronunciationSourceToken(9, 13, "시니소", "死にそう"),
    )

    @Test fun `native visual wrap inserts reading immediately after each source row`() {
        val result = requireNotNull(AppleInterleavedReadingPolicy.plan(
            sourceText = source,
            pronunciation = pronunciation,
            cells = listOf(
                AppleVisualSourceCell("瞳孔", 0, 42),
                AppleVisualSourceCell("バチ", 0, 42),
                AppleVisualSourceCell("開いて", 0, 42),
                AppleVisualSourceCell("溺れ", 0, 42),
                AppleVisualSourceCell("死にそう", 54, 96),
            ),
            spans = spans,
        ))
        assertEquals(listOf(3, 4), result.map { it.insertAfterSourceCell })
        assertEquals(listOf("도코 바치 히라이테 오보레", "시니소"),
            result.map { it.text })
        assertEquals(pronunciation.substring(result[0].readingStart, result[0].readingEnd),
            result[0].text)
        assertEquals(pronunciation.substring(result[1].readingStart, result[1].readingEnd),
            result[1].text)
        assertEquals(pronunciation.indexOf("시니소"), result[1].readingStart)
        assertEquals("도코바치히라이테오보레시니소",
            result.joinToString("") { it.text }.filter(Char::isLetterOrDigit))
    }

    @Test fun `one native visual row remains on the existing single-row path`() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, pronunciation,
            listOf(
                AppleVisualSourceCell("瞳孔バチ開いて", 0, 42),
                AppleVisualSourceCell("溺れ死にそう", 0, 42),
            ), spans,
        ))
    }

    @Test fun `a grouped phonetic token is never cut mid Hangul syllable or duplicated`() {
        val result = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "かなかな夜", "카나카나 요루",
            listOf(
                AppleVisualSourceCell("かな", 0, 40),
                AppleVisualSourceCell("かな", 52, 92),
                AppleVisualSourceCell("夜", 52, 92),
            ),
            listOf(
                ApplePronunciationSourceToken(0, 4, "카나카나", "かなかな"),
                ApplePronunciationSourceToken(4, 5, "요루", "夜"),
            ),
        ))
        assertEquals(listOf("카나카나", "요루"), result.map { it.text })
        assertEquals(listOf(0, 2), result.map { it.insertAfterSourceCell })
    }

    @Test fun `incomplete or mismatched reading anchors cannot reorder source cells`() {
        val cells = listOf(
            AppleVisualSourceCell("瞳孔", 0, 42),
            AppleVisualSourceCell("バチ開いて溺れ死にそう", 55, 98),
        )
        assertNull(AppleInterleavedReadingPolicy.plan(source, pronunciation, cells,
            spans.dropLast(1)))
        assertNull(AppleInterleavedReadingPolicy.plan(source, pronunciation, cells,
            spans.dropLast(1) + ApplePronunciationSourceToken(9, 13, "시니소", "違う")))
        assertNull(AppleInterleavedReadingPolicy.plan(source, pronunciation,
            listOf(AppleVisualSourceCell("別の歌", 0, 42), cells[1]), spans))
    }

    @Test fun `unmeasured cells do not generate speculative wrap boundaries`() {
        assertNull(AppleInterleavedReadingPolicy.plan(source, pronunciation,
            listOf(
                AppleVisualSourceCell("瞳孔バチ", 0, 0),
                AppleVisualSourceCell("開いて溺れ死にそう", 0, 0),
            ), spans))
    }

    @Test fun `three Japanese rows produce three immediate reading placements`() {
        val result = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "あいうえお", "아 이 우 에 오",
            listOf(
                AppleVisualSourceCell("あい", 0, 42),
                AppleVisualSourceCell("うえ", 51, 94),
                AppleVisualSourceCell("お", 105, 148),
            ),
            listOf(
                ApplePronunciationSourceToken(0, 1, "아", "あ"),
                ApplePronunciationSourceToken(1, 2, "이", "い"),
                ApplePronunciationSourceToken(2, 3, "우", "う"),
                ApplePronunciationSourceToken(3, 4, "에", "え"),
                ApplePronunciationSourceToken(4, 5, "오", "お"),
            ),
        ))
        assertEquals(listOf(0, 1, 2), result.map { it.insertAfterSourceCell })
        assertEquals(listOf("아 이", "우 에", "오"), result.map { it.text })
    }

    @Test fun `native baseline jitter inside a row does not create false rows`() {
        val result = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, pronunciation,
            listOf(
                AppleVisualSourceCell("瞳孔", 0, 42),
                AppleVisualSourceCell("バチ", 3, 45),
                AppleVisualSourceCell("開いて", 1, 43),
                AppleVisualSourceCell("溺れ", 2, 44),
                AppleVisualSourceCell("死にそう", 55, 99),
            ), spans,
        ))
        assertEquals(listOf(3, 4), result.map { it.insertAfterSourceCell })
    }

    @Test fun `coarse whole line clock survives visual row interleaving unchanged`() {
        val first = AppleInterleavedReadingRow(0, "도코 바치", 0, 5)
        val second = AppleInterleavedReadingRow(1, "시니소", 6, 9)
        val timing = listOf(listOf(AppleTimedReadingUnit(0, 9, 1000L, 4000L)))
        val firstUnits = AppleInterleavedReadingTimingPolicy.project(
            variants = timing,
            fullPronunciation = "도코 바치 시니소",
            rows = listOf(first, second),
            row = first,
        )
        val secondUnits = AppleInterleavedReadingTimingPolicy.project(
            variants = timing,
            fullPronunciation = "도코 바치 시니소",
            rows = listOf(first, second),
            row = second,
        )
        assertEquals(listOf(AppleTimedReadingUnit(0, 5, 1000L, 4000L)),
            firstUnits.single())
        assertEquals(listOf(AppleTimedReadingUnit(0, 3, 1000L, 4000L)),
            secondUnits.single())
    }

    @Test fun `unknown cross row word timing is never sliced into invented syllable clocks`() {
        val first = AppleInterleavedReadingRow(0, "도코", 0, 2)
        val second = AppleInterleavedReadingRow(1, "바치", 3, 5)
        val timing = listOf(listOf(AppleTimedReadingUnit(1, 5, 1000L, 3000L)))
        assertEquals(emptyList<AppleTimedReadingUnit>(),
            AppleInterleavedReadingTimingPolicy.project(
                timing, "도코 바치", listOf(first, second), first,
            ).single())
        assertEquals(emptyList<AppleTimedReadingUnit>(),
            AppleInterleavedReadingTimingPolicy.project(
                timing, "도코 바치", listOf(first, second), second,
            ).single())
    }
}
