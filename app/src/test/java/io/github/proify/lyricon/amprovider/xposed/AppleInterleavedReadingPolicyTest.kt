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
}
