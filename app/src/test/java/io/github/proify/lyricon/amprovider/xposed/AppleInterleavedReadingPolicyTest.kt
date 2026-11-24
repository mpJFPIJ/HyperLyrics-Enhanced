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

    @Test fun literalKanaNativeWordsNeedAnExactIndependentReadingForEveryWord() {
        val exact = AppleInterleavedReadingPolicy.verifiedKanaWords(
            "かなあい", listOf("かな", "あい"), "카나 아이",
        )
        assertEquals(listOf("카나", "아이"), exact?.map { it.hangul })
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            "きゃ", listOf("き", "ゃ"), "캬",
        ))
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            "世界", listOf("世", "界"), "세카이",
        ))
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            "漢は字", listOf("漢", "は", "字"), "가하와나",
        ))
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            "かな", listOf("か", "は"), "카나",
        ))
    }

    @Test fun internallyWrappedNativeCellCannotStandForOneFlexRow() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あいう", "아 이 우", listOf(
                AppleVisualSourceCell("あい", 0, 90, nativeLineCount = 2),
                AppleVisualSourceCell("う", 100, 142),
            ), emptyList(),
        ))
    }

    @Test fun internallyWrappedSingleNativeTextViewUsesItsRealLineOffsetsNotOneLogicalCell() {
        val rows = requireNotNull(AppleNativeSoftWrapReadingPolicy.plan(
            sourceText = "駄目は",
            displayedText = "駄目は",
            pronunciation = "다메와",
            visualLineRanges = listOf(0 until 1, 1 until 3),
            spans = listOf(
                ApplePronunciationSourceToken(0, 1, "다", "駄"),
                ApplePronunciationSourceToken(1, 2, "메", "目"),
                ApplePronunciationSourceToken(2, 3, "와", "は"),
            ),
        ))
        assertEquals(listOf("다", "메와"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
        assertEquals("다메와", rows.joinToString("") { it.text })
    }

    @Test fun internallyWrappedCompoundCannotInventAReadingBreakpoint() {
        assertNull(AppleNativeSoftWrapReadingPolicy.plan(
            "世界", "世界", "세카이", listOf(0 until 1, 1 until 2),
            listOf(ApplePronunciationSourceToken(0, 2, "세카이", "世界")),
        ))
        assertNull(AppleNativeSoftWrapReadingPolicy.plan(
            "駄目", "駄目", "다메", listOf(0 until 1, 0 until 2),
            listOf(
                ApplePronunciationSourceToken(0, 1, "다", "駄"),
                ApplePronunciationSourceToken(1, 2, "메", "目"),
            ),
        ))
    }

    @Test fun separatelyDictionaryVerifiedKanjiCharactersCanFollowRealInternalTextLines() {
        val rows = requireNotNull(AppleNativeSoftWrapReadingPolicy.plan(
            "世界", "世界", "세카이", listOf(0 until 1, 1 until 2),
            listOf(
                ApplePronunciationSourceToken(0, 1, "세", "世"),
                ApplePronunciationSourceToken(1, 2, "카이", "界"),
            ),
        ))
        assertEquals(listOf("세", "카이"), rows.map { it.text })
    }

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

    @Test fun `a verified kana token spanning two native rows is split at the exact kana boundary`() {
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
        assertEquals(listOf("카나", "카나 요루"), result.map { it.text })
        assertEquals(listOf(0, 2), result.map { it.insertAfterSourceCell })
        assertEquals("카나카나요루", result.joinToString("") { it.text }
            .filter(Char::isLetterOrDigit))
    }

    @Test fun `a compound kanji token crossing source rows cannot claim guessed phonetic boundaries`() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            "世界", "세카이",
            listOf(
                AppleVisualSourceCell("世", 0, 40),
                AppleVisualSourceCell("界", 52, 92),
            ),
            listOf(ApplePronunciationSourceToken(0, 2, "세카이", "世界")),
        ))
    }

    @Test fun `verified kana suffix can locate the remaining sound of one Kanji fragment`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "脳みそ", "노미소",
            listOf(
                AppleVisualSourceCell("脳", 0, 42),
                AppleVisualSourceCell("みそ", 55, 97),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "노미소", "脳みそ")),
        ))
        assertEquals(listOf("노", "미소"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
        assertEquals("노미소", rows.joinToString("") { it.text })
    }

    @Test fun `IRIS OUT Kanji verb split before its final kana remains source anchored`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "喚く", "와메쿠",
            listOf(
                AppleVisualSourceCell("喚", 0, 42),
                AppleVisualSourceCell("く", 55, 97),
            ),
            listOf(ApplePronunciationSourceToken(0, 2, "와메쿠", "喚く")),
        ))
        assertEquals(listOf("와메", "쿠"), rows.map { it.text })
    }

    @Test fun `known kana prefix may identify one Kanji suffix without estimating character lengths`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "みそ脳", "미소노",
            listOf(
                AppleVisualSourceCell("みそ", 0, 42),
                AppleVisualSourceCell("脳", 55, 97),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "미소노", "みそ脳")),
        ))
        assertEquals(listOf("미소", "노"), rows.map { it.text })
    }

    @Test fun `two unknown Kanji row sounds cannot borrow one another's syllables`() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            "歌の声", "우타노코에",
            listOf(
                AppleVisualSourceCell("歌", 0, 40),
                AppleVisualSourceCell("の", 55, 95),
                AppleVisualSourceCell("声", 110, 150),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "우타노코에", "歌の声")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "脳みそ", "노미소",
            listOf(
                AppleVisualSourceCell("脳", 0, 40),
                AppleVisualSourceCell("みそ", 55, 95),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "노미사", "脳みそ")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "脳みそ", "노마소",
            listOf(
                AppleVisualSourceCell("脳", 0, 40),
                AppleVisualSourceCell("みそ", 55, 95),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "노마소", "脳みそ")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "脳みそ", "ㄴ미소",
            listOf(
                AppleVisualSourceCell("脳", 0, 40),
                AppleVisualSourceCell("みそ", 55, 95),
            ),
            listOf(ApplePronunciationSourceToken(0, 3, "ㄴ미소", "脳みそ")),
        ))
    }

    @Test fun `independently verified fine kanji tokens permit exact immediate visual row readings`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "世界", pronunciation = "세카이",
            cells = listOf(
                AppleVisualSourceCell("世", 0, 40),
                AppleVisualSourceCell("界", 55, 95),
            ),
            spans = listOf(
                ApplePronunciationSourceToken(0, 1, "세", "世"),
                ApplePronunciationSourceToken(1, 2, "카이", "界"),
            ),
        ))
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
        assertEquals(listOf("세", "카이"), rows.map { it.text })
    }

    @Test fun `full sentence particle reading stays with its actual Japanese visual row`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "駄目は", "다메와",
            listOf(
                AppleVisualSourceCell("駄", 0, 40),
                AppleVisualSourceCell("目", 0, 40),
                AppleVisualSourceCell("は", 55, 95),
            ),
            listOf(
                ApplePronunciationSourceToken(0, 1, "다", "駄"),
                ApplePronunciationSourceToken(1, 2, "메", "目"),
                ApplePronunciationSourceToken(2, 3, "와", "は"),
            ),
        ))
        assertEquals(listOf("다메", "와"), rows.map { it.text })
        assertEquals(listOf(1, 2), rows.map { it.insertAfterSourceCell })
    }

    @Test fun `literal kana can derive exact per-row readings when token spans are unavailable`() {
        val source = "かなかな"
        val reading = "카나카나"
        assertEquals(true, AppleInterleavedReadingPolicy.canDeriveKanaRows(source, reading))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, reading,
            listOf(
                AppleVisualSourceCell("かな", 0, 40),
                AppleVisualSourceCell("かな", 52, 92),
            ),
            spans = emptyList(),
        ))
        assertEquals(listOf("카나", "카나"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
        assertEquals(false, AppleInterleavedReadingPolicy.canDeriveKanaRows("世界", "세카이"))
    }

    @Test fun `a punctuation-only visual row does not collect or suppress adjacent readings`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "あ!い", "아 이",
            listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("!", 52, 92),
                AppleVisualSourceCell("い", 103, 143),
            ),
            listOf(
                ApplePronunciationSourceToken(0, 1, "아", "あ"),
                ApplePronunciationSourceToken(2, 3, "이", "い"),
            ),
        ))
        assertEquals(listOf(0, 2), rows.map { it.insertAfterSourceCell })
        assertEquals(listOf("아", "이"), rows.map { it.text })
        val inferred = AppleInterleavedReadingPolicy.plan(
            "あ!い", "아 이",
            listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("!", 52, 92),
                AppleVisualSourceCell("い", 103, 143),
            ),
            spans = emptyList(),
        )
        assertEquals(rows, inferred)
    }

    @Test fun `small kana digraph crossing native rows is never split into a false sound`() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            "きゃ", "캬",
            listOf(
                AppleVisualSourceCell("き", 0, 40),
                AppleVisualSourceCell("ゃ", 52, 92),
            ),
            listOf(ApplePronunciationSourceToken(0, 2, "캬", "きゃ")),
        ))
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

    @Test fun `one coarse line clock cannot animate independent visual rows`() {
        val text = "도코 바치 시니소"
        val first = AppleInterleavedReadingRow(0, "도코 바치", 0, 5)
        val second = AppleInterleavedReadingRow(1, "시니소", 6, 9)
        val timing = AppleTimedPronunciationHighlightPolicy.units(
            "元の歌", text, emptyList(), lineBeginMs = 1000L, lineEndMs = 4000L,
        )
        assertEquals(true, timing.single().isWholeLine)
        val rows = listOf(first, second)
        rows.forEach { row ->
            assertEquals(listOf(emptyList<AppleTimedReadingUnit>()),
                AppleInterleavedReadingTimingPolicy.project(listOf(timing), text, rows, row))
        }
        val unsplit = AppleInterleavedReadingRow(1, text, 0, text.length)
        assertEquals(listOf(timing), AppleInterleavedReadingTimingPolicy.project(
            listOf(timing), text, listOf(unsplit), unsplit,
        ))
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
