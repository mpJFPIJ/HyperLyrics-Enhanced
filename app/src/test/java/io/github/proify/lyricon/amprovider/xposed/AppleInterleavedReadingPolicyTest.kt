package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.root.aitrans.AppleNativeReadingProjection
import com.juren233.hyperlyricsenhanced.root.aitrans.SourceReadingSpan
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

    @Test fun missingRawSourceIdentityCannotAssignAnUnprovenKanjiSoundToVisualRows() {
        val source = "世あ"
        val cells = listOf(
            AppleVisualSourceCell("世", 0, 40),
            AppleVisualSourceCell("あ", 53, 93),
        )
        val missingIdentity = listOf(
            ApplePronunciationSourceToken(0, 1, "세"),
            ApplePronunciationSourceToken(1, 2, "아", "あ"),
        )
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            source, "세아", missingIdentity,
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, "세아", cells, missingIdentity,
        ))

        val proven = missingIdentity.mapIndexed { index, token ->
            if (index == 0) token.copy(sourceText = "世") else token
        }
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "세아", cells, proven,
        ))
        assertEquals(listOf("세", "아"), rows.map { it.text })
    }

    @Test fun singleUnknownKanjiRowBetweenExactSoundAnchorsKeepsItsResidualReading() {
        // A partial dictionary map does not prove *each Japanese word*, but
        // a single missing sound interval can belong to one measured Flex row.
        val source = "愛してた"
        val anchor = ApplePronunciationSourceToken(2, 4, "테타", "てた")
        val cells = listOf(
            AppleVisualSourceCell("愛し", 0, 40),
            AppleVisualSourceCell("てた", 54, 96),
        )
        assertEquals(true, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            source, "아이시 테타", listOf(anchor),
        ))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "아이시 테타", cells, listOf(anchor),
        ))
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
        assertEquals(listOf("아이시", "테타"), rows.map { it.text })
        assertEquals("아이시테타", rows.joinToString("") { it.text }
            .filter(Char::isLetterOrDigit))
        val native = AppleTimedPronunciationHighlightPolicy.units(
            source, "아이시 테타", listOf(
                AppleTimedSourceWord("愛", 1_000L, 1_200L),
                AppleTimedSourceWord("し", 1_200L, 1_450L),
                AppleTimedSourceWord("てた", 1_600L, 1_950L),
            ), phoneticSpans = listOf(anchor),
        )
        val projected = rows.map { row ->
            AppleInterleavedReadingTimingPolicy.project(
                listOf(native), "아이시 테타", rows, row, verifiedCrossRow = true,
            ).single()
        }
        // An inferred WHOLE visual-row sound is not per-word phonetic proof.
        // Its Japanese words remain on their original clocks, while Hangul
        // paint only inherits the separately verified suffix's real clock.
        assertEquals(emptyList<AppleTimedReadingUnit>(), projected[0])
        assertEquals(listOf(1_600L), projected[1].map { it.beginMs })
        assertEquals(listOf(1_950L), projected[1].map { it.endMs })
        assertEquals(listOf(2), projected[1].map { it.sourceWordIndex })
    }

    @Test fun oneVisualKanjiRowCanBeReadWithoutDividingTwoUnknownNativeWordClocks() {
        val source = "愛恋して"
        val phonetic = listOf(ApplePronunciationSourceToken(2, 4, "시테", "して"))
        val originalWords = listOf("愛", "恋", "して")
        // Two Apple word clocks share the unknown Kanji sound: native-word
        // projection must refuse, even though both cells occupy ONE Flex row.
        assertNull(AppleNativeReadingProjection.projectForNativeKaraoke(
            original = source,
            appleWords = originalWords,
            spans = listOf(SourceReadingSpan(2, 4, "して", "", "시테")),
            pronunciation = "아이코이 시테",
        ))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            sourceText = source, pronunciation = "아이코이 시테",
            cells = listOf(
                AppleVisualSourceCell("愛", 0, 42),
                AppleVisualSourceCell("恋", 0, 42),
                AppleVisualSourceCell("して", 55, 97),
            ), spans = phonetic,
        ))
        assertEquals(listOf(1, 2), rows.map { it.insertAfterSourceCell })
        assertEquals(listOf("아이코이", "시테"), rows.map { it.text })
    }

    @Test fun isolatedSupplementaryHanGapUsesCodePointCompleteSourceIdentity() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "𠮟あ", "시츠아", listOf(
                AppleVisualSourceCell("𠮟", 0, 44),
                AppleVisualSourceCell("あ", 60, 104),
            ), listOf(ApplePronunciationSourceToken(2, 3, "아", "あ")),
        ))
        assertEquals(listOf("시츠", "아"), rows.map { it.text })
    }

    @Test fun oneMiddleKanjiGapKeepsBothIndependentlyAnchoredNeighbors() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "こ愛して", "코아이시테", listOf(
                AppleVisualSourceCell("こ", 0, 40),
                AppleVisualSourceCell("愛", 52, 92),
                AppleVisualSourceCell("して", 104, 144),
            ), listOf(
                ApplePronunciationSourceToken(0, 1, "코", "こ"),
                ApplePronunciationSourceToken(2, 4, "시테", "して"),
            ),
        ))
        assertEquals(listOf("코", "아이", "시테"), rows.map { it.text })
        assertEquals(listOf(0, 1, 2), rows.map { it.insertAfterSourceCell })
    }

    @Test fun partialAnchorsNeverSplitTwoUnknownRowsOrAnUnverifiedKanaGap() {
        // The residual interval crosses two source Flex rows. It cannot be
        // divided into word clocks, character ratios or guessed Japanese rows.
        val twoRows = listOf(
            AppleVisualSourceCell("愛", 0, 40),
            AppleVisualSourceCell("し", 52, 92),
            AppleVisualSourceCell("て", 104, 144),
        )
        assertEquals(true, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛して", "아이시테", listOf(
                ApplePronunciationSourceToken(2, 3, "테", "て"),
            ),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "愛して", "아이시테", twoRows,
            listOf(ApplePronunciationSourceToken(2, 3, "테", "て")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "愛の恋", "아이노코이", listOf(
                AppleVisualSourceCell("愛", 0, 40),
                AppleVisualSourceCell("の", 52, 92),
                AppleVisualSourceCell("恋", 104, 144),
            ), listOf(ApplePronunciationSourceToken(1, 2, "노", "の")),
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "あい", "나이", listOf(ApplePronunciationSourceToken(1, 2, "이", "い")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あい", "나이", listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("い", 52, 92),
            ), listOf(ApplePronunciationSourceToken(1, 2, "이", "い")),
        ))
        // A lone unknown Han does not make adjacent literal kana silent or
        // give that kana an arbitrary sound via residual subtraction.
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛して", "아이테",
            listOf(ApplePronunciationSourceToken(2, 3, "테", "て")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "愛して", "아이테", listOf(
                AppleVisualSourceCell("愛し", 0, 40),
                AppleVisualSourceCell("て", 52, 92),
            ), listOf(ApplePronunciationSourceToken(2, 3, "테", "て")),
        ))
    }

    @Test fun partialAnchorsRejectRepeatedSoundAndTamperedRawOffsets() {
        val source = "愛か恋か"
        val cells = listOf(
            AppleVisualSourceCell("愛か恋", 0, 40),
            AppleVisualSourceCell("か", 52, 92),
        )
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            source, "아이카코이카",
            listOf(ApplePronunciationSourceToken(3, 4, "카", "か")),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, "아이카코이카", cells,
            listOf(ApplePronunciationSourceToken(3, 4, "카", "か")),
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛して", "아이시테",
            listOf(ApplePronunciationSourceToken(2, 3, "테", "手")),
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛して", "아이시ㄴ",
            listOf(ApplePronunciationSourceToken(2, 3, "ㄴ", "て")),
        ))
    }

    @Test fun partialKanjiGapKeepsKanaSeparatedByNativeSourceWhitespace() {
        // こう is a long-vowel sound "코", but こ う must be checked as
        // two literal fragments "코" + "우". Silent normalization would
        // wrongly assign 아이코 to the Japanese "愛こ う" source row.
        val cells = listOf(
            AppleVisualSourceCell("愛こ う", 0, 45),
            AppleVisualSourceCell("て", 56, 101),
        )
        val anchor = listOf(ApplePronunciationSourceToken(4, 5, "테", "て"))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛こ うて", "아이코테", anchor,
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "愛こ うて", "아이코테", cells, anchor,
        ))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "愛こ うて", "아이코우테", cells, anchor,
        ))
        assertEquals(listOf("아이코우", "테"), rows.map { it.text })
    }

    @Test fun supplementaryNonHangulInAnchorOrSelectedSoundCannotHideInUtf16Halves() {
        assertEquals(false, AppleInterleavedReadingPolicy.canDeriveKanaRows(
            "あ", "아𠮟",
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.canDeriveKanaRows(
            "か", "카\u3099",
        ))
        val complete = listOf(
            ApplePronunciationSourceToken(0, 1, "아이", "愛"),
            ApplePronunciationSourceToken(1, 2, "테𠮟", "て"),
        )
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛て", "아이테", complete,
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "愛て", "아이테", listOf(
                AppleVisualSourceCell("愛", 0, 40),
                AppleVisualSourceCell("て", 52, 92),
            ), complete,
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛て", "아이테",
            listOf(ApplePronunciationSourceToken(1, 2, "테𠮟", "て")),
        ))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "愛て", "아이테𠮟", listOf(
                ApplePronunciationSourceToken(0, 1, "아이", "愛"),
                ApplePronunciationSourceToken(1, 2, "테", "て"),
            ),
        ))
    }

    @Test fun JapaneseVoicingMarksCannotDetachFromTheirKanaSourceAnchor() {
        listOf('\u3099', '\u309A', '\u309B', '\u309C').forEach { mark ->
            val source = "愛か${mark}て"
            val detached = listOf(
                ApplePronunciationSourceToken(1, 2, "카", "か"),
                ApplePronunciationSourceToken(3, 4, "테", "て"),
            )
            assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
                source, "아이카테", detached,
            ))
            assertNull(AppleInterleavedReadingPolicy.plan(
                source, "아이카테", listOf(
                    AppleVisualSourceCell("愛", 0, 44),
                    AppleVisualSourceCell("か${mark}て", 56, 100),
                ), detached,
            ))
            val completeButDetached = listOf(
                ApplePronunciationSourceToken(0, 1, "아이", "愛"),
            ) + detached
            assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
                source, "아이카테", completeButDetached,
            ))
        }
        // The mark itself is not prohibited: a genuine raw token that owns
        // both the Kana base and its voicing mark is still a valid anchor.
        val attached = "愛か\u3099て"
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            attached, "아이가테", listOf(
                AppleVisualSourceCell("愛", 0, 44),
                AppleVisualSourceCell("か\u3099て", 56, 100),
            ), listOf(
                ApplePronunciationSourceToken(0, 1, "아이", "愛"),
                ApplePronunciationSourceToken(1, 3, "가", "か\u3099"),
                ApplePronunciationSourceToken(3, 4, "테", "て"),
            ),
        ))
        assertEquals(listOf("아이", "가테"), rows.map { it.text })
    }

    @Test fun measuredNativeSourceCannotSilentlyOmitItsJapaneseVoicingMark() {
        val source = "か\u3099て"
        val spans = listOf(
            ApplePronunciationSourceToken(0, 2, "가", "か\u3099"),
            ApplePronunciationSourceToken(2, 3, "테", "て"),
        )
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, "가테", listOf(
                AppleVisualSourceCell("か", 0, 40),
                AppleVisualSourceCell("て", 52, 92),
            ), spans,
        ))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "가테", listOf(
                AppleVisualSourceCell("か\u3099", 0, 40),
                AppleVisualSourceCell("て", 52, 92),
            ), spans,
        ))
        assertEquals(listOf("가", "테"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
    }

    @Test fun intactDecomposedKanaCanInterleaveMeasuredRowsWithoutProviderTokens() {
        val source = "か\u3099て"
        assertEquals(true, AppleInterleavedReadingPolicy.canDeriveKanaRows(source, "가테"))
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "가테", listOf(
                AppleVisualSourceCell("か\u3099", 0, 40),
                AppleVisualSourceCell("て", 54, 94),
            ), emptyList(),
        ))
        assertEquals(listOf("가", "테"), rows.map { it.text })
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, "가테", listOf(
                AppleVisualSourceCell("か", 0, 40),
                AppleVisualSourceCell("\u3099て", 54, 94),
            ), emptyList(),
        ))
    }

    @Test fun fullMixedTokenCannotCrossRowImmediatelyBeforeItsVoicingMark() {
        for (mark in listOf("\u3099", "\u309A", "\uFF9E", "\uFF9F")) {
            val source = "か${mark}愛"
            assertNull(AppleInterleavedReadingPolicy.plan(
                source, "카아이", listOf(
                    AppleVisualSourceCell("か", 0, 40),
                    AppleVisualSourceCell("${mark}愛", 52, 92),
                ), spans = listOf(
                    ApplePronunciationSourceToken(0, source.length, "카아이", source),
                ),
            ))
            assertNull(AppleInterleavedReadingPolicy.verifiedFragmentSounds(
                listOf("か", "${mark}愛"), "카아이",
            ))
        }
        assertEquals(listOf("가", "아이"),
            AppleInterleavedReadingPolicy.verifiedFragmentSounds(
                listOf("が", "愛"), "가아이",
            ))
    }

    @Test fun punctuationAndOrphanVoicingMarkCannotFormIndependentSpokenSourceToken() {
        for (mark in listOf("\u3099", "\u309B", "\uFF9E", "\uFF9F")) {
            val source = "愛!${mark}て"
            val spans = listOf(
                ApplePronunciationSourceToken(0, 1, "아이", "愛"),
                ApplePronunciationSourceToken(1, 2 + mark.length, "바", "!${mark}"),
                ApplePronunciationSourceToken(2 + mark.length, source.length, "테", "て"),
            )
            assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
                source, "아이바테", spans,
            ))
            assertNull(AppleInterleavedReadingPolicy.plan(
                source, "아이바테", listOf(
                    AppleVisualSourceCell("愛", 0, 40),
                    AppleVisualSourceCell("!${mark}", 52, 92),
                    AppleVisualSourceCell("て", 105, 145),
                ), spans,
            ))
        }
    }

    @Test fun singleNativeTextViewAllowsOnlyOnePhysicallyMeasuredPartialGapLine() {
        val anchor = listOf(ApplePronunciationSourceToken(2, 3, "테", "て"))
        val rows = requireNotNull(AppleNativeSoftWrapReadingPolicy.plan(
            "愛して", "愛して", "아이시테",
            listOf(0 until 2, 2 until 3), anchor,
        ))
        assertEquals(listOf("아이시", "테"), rows.map { it.text })
        assertNull(AppleNativeSoftWrapReadingPolicy.plan(
            "愛して", "愛して", "아이시테",
            listOf(0 until 1, 1 until 2, 2 until 3), anchor,
        ))
    }

    @Test fun measuredFlexRowsDistinguishMultipleNativeCellsFromInternalTextWraps() {
        // Apple's 6.5.3 karaoke XML declares each word TextView single-line.
        // Three source cells can still occupy two visual flex rows.
        assertEquals(2, AppleMeasuredFlexRowPolicy.count(listOf(
            0 to 44, 0 to 44, 54 to 98,
        )))
        assertEquals(listOf(0, 0, 1), AppleMeasuredFlexRowPolicy.rows(listOf(
            0 to 44, 0 to 44, 54 to 98,
        ))?.toList())
        assertEquals(1, AppleMeasuredFlexRowPolicy.count(listOf(
            0 to 44, 2 to 46, 3 to 44,
        )))
        assertNull(AppleMeasuredFlexRowPolicy.count(emptyList()))
        assertNull(AppleMeasuredFlexRowPolicy.count(listOf(0 to 0, 10 to 50)))
    }

    @Test fun chainedOverlapsWithoutOneSharedVerticalBandAreNotOneMeasuredRow() {
        // Neighbor overlaps are insufficient: [0,10) and [16,26) share no
        // ink region even though the middle [8,18) bridges both of them.
        val unsupported = listOf(0 to 10, 8 to 18, 16 to 26, 30 to 40)
        assertNull(AppleMeasuredFlexRowPolicy.rows(unsupported))
        assertNull(AppleMeasuredFlexRowPolicy.count(unsupported))
        assertNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "あいうえ", pronunciation = "아이우에",
            cells = listOf(
                AppleVisualSourceCell("あ", 0, 10),
                AppleVisualSourceCell("い", 8, 18),
                AppleVisualSourceCell("う", 16, 26),
                AppleVisualSourceCell("え", 30, 40),
            ), spans = listOf(
                ApplePronunciationSourceToken(0, 4, "아이우에", "あいうえ"),
            ),
        ))
        // A true shared band survives modest top/bottom offsets.
        assertEquals(listOf(0, 0, 0, 1), AppleMeasuredFlexRowPolicy.rows(listOf(
            0 to 10, 3 to 13, 6 to 16, 30 to 40,
        ))?.toList())
    }

    @Test fun `reversed disjoint native child positions cannot assign Hangul to the wrong row`() {
        val reversed = listOf(60 to 100, 0 to 40, 115 to 155)
        // The second source child is entirely ABOVE the first child despite
        // following it in native source order. Overlap is allowed within a
        // real Flexbox row; this fully reversed geometry is not row proof.
        assertNull(AppleMeasuredFlexRowPolicy.rows(reversed))
        assertNull(AppleMeasuredFlexRowPolicy.count(reversed))
        assertNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "あいう", pronunciation = "아이우",
            cells = listOf(
                AppleVisualSourceCell("あ", 60, 100),
                AppleVisualSourceCell("い", 0, 40),
                AppleVisualSourceCell("う", 115, 155),
            ),
            spans = emptyList(),
        ))
        // Different top positions can still belong to the same row when
        // their measured rectangles genuinely overlap.
        assertEquals(listOf(0, 0, 1), AppleMeasuredFlexRowPolicy.rows(listOf(
            10 to 60, 0 to 40, 70 to 110,
        ))?.toList())
    }

    @Test fun `ambiguous cross-row overlap cannot inherit the following source row`() {
        // Child 2 starts a new lower row; child 3 then reaches back up into
        // the first row while still overlapping child 2. Neither a simple
        // previous-top reversal check nor a greedy row-bottom pass rejects it.
        val crossing = listOf(0 to 40, 70 to 110, 35 to 75)
        assertNull(AppleMeasuredFlexRowPolicy.rows(crossing))
        assertNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "あいう", pronunciation = "아이우",
            cells = listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("い", 70, 110),
                AppleVisualSourceCell("う", 35, 75),
            ),
            spans = emptyList(),
        ))
        // Peers on the lower row may overlap each other, provided neither
        // crosses back into the earlier row's measured vertical interval.
        assertEquals(listOf(0, 1, 1), AppleMeasuredFlexRowPolicy.rows(listOf(
            0 to 40, 70 to 110, 75 to 105,
        ))?.toList())
    }

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

    @Test fun halfwidthKanaKeepsItsMeasuredRowsAndDictionaryCrossRowSound() {
        val source = "ｶﾞﾊﾛｰ"
        val words = listOf("ｶﾞ", "ﾊﾛｰ")
        val nativeCells = listOf(
            AppleVisualSourceCell(words[0], 0, 40),
            AppleVisualSourceCell(words[1], 52, 92),
        )
        assertEquals(true, AppleInterleavedReadingPolicy.canDeriveKanaRows(source, "가하로"))
        assertEquals(listOf("가", "하로"),
            AppleInterleavedReadingPolicy.verifiedKanaWords(source, words, "가하로")
                ?.map { it.hangul })
        val derived = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "가하로", nativeCells, spans = emptyList(),
        ))
        assertEquals(listOf("가", "하로"), derived.map { it.text })
        assertEquals(listOf(0, 1), derived.map { it.insertAfterSourceCell })

        val dictionary = requireNotNull(AppleInterleavedReadingPolicy.plan(
            source, "가하로", nativeCells,
            spans = listOf(ApplePronunciationSourceToken(0, source.length, "가하로", source)),
        ))
        assertEquals(derived, dictionary)
        assertEquals(listOf("가", "하로"), AppleInterleavedReadingPolicy.verifiedFragmentSounds(
            words, "가하로",
        ))
    }

    @Test fun halfwidthDakutenSplitBetweenPhysicalRowsNeverClaimsAProvenBoundary() {
        val source = "ｶﾞ"
        assertNull(AppleInterleavedReadingPolicy.plan(
            source, "가", listOf(
                AppleVisualSourceCell("ｶ", 0, 40),
                AppleVisualSourceCell("ﾞ", 52, 92),
            ), listOf(ApplePronunciationSourceToken(0, source.length, "가", source)),
        ))
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            source, listOf("ｶ", "ﾞ"), "가",
        ))
    }

    @Test fun internallyWrappedNativeCellCannotStandForOneFlexRow() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あいう", "아 이 우", listOf(
                AppleVisualSourceCell("あい", 0, 90, nativeLineCount = 2),
                AppleVisualSourceCell("う", 100, 142),
            ), emptyList(),
        ))
        // Perfect phonetic spans still do not create a physical insertion
        // point BETWEEN the two Layout lines inside the first native child.
        assertNull(AppleInterleavedReadingPolicy.plan(
            "駄目は", "다메와", listOf(
                AppleVisualSourceCell("駄目", 0, 88, nativeLineCount = 2),
                AppleVisualSourceCell("は", 90, 130, nativeLineCount = 1),
            ), listOf(
                ApplePronunciationSourceToken(0, 1, "다", "駄"),
                ApplePronunciationSourceToken(1, 2, "메", "目"),
                ApplePronunciationSourceToken(2, 3, "와", "は"),
            ),
        ))
    }

    @Test fun soleTerminalWrappedCellPreservesEarlierMeasuredFlexRowReadings() {
        // The last native cell occupies its own, final Flex row. No reading
        // child is inserted BETWEEN its internal TextView.Layout lines.
        val kana = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "あいう", "아이우", listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("いう", 52, 144, nativeLineCount = 2),
            ), emptyList(),
        ))
        assertEquals(listOf(0, 1), kana.map { it.insertAfterSourceCell })
        assertEquals(listOf("아", "이우"), kana.map { it.text })
        val nativeClocks = AppleTimedPronunciationHighlightPolicy.units(
            "あいう", "아이우", listOf(
                AppleTimedSourceWord("あ", 1_000L, 1_200L),
                AppleTimedSourceWord("いう", 1_400L, 1_900L),
            ),
        )
        assertEquals(listOf(0, 1), nativeClocks.map { it.sourceWordIndex })
        val projected = kana.map { row ->
            AppleInterleavedReadingTimingPolicy.project(
                listOf(nativeClocks), "아이우", kana, row, verifiedCrossRow = true,
            ).single()
        }
        assertEquals(listOf(1_000L, 1_400L), projected.map { it.single().beginMs })
        assertEquals(listOf(1_200L, 1_900L), projected.map { it.single().endMs })

        val dictionary = requireNotNull(AppleInterleavedReadingPolicy.plan(
            "駄目かな", "다메카나", listOf(
                AppleVisualSourceCell("駄目", 0, 40),
                AppleVisualSourceCell("かな", 53, 144, nativeLineCount = 2),
            ), listOf(
                ApplePronunciationSourceToken(0, 2, "다메", "駄目"),
                ApplePronunciationSourceToken(2, 4, "카나", "かな"),
            ),
        ))
        assertEquals(listOf("다메", "카나"), dictionary.map { it.text })
    }

    @Test fun wrappedCellWithFinalRowPeerStillHasNoProvenInternalInsertionSlot() {
        // Even though the Flex has two rows, the wrapped last word shares
        // the final row with another source cell. Keep the guarded fallback.
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あいうえ", "아이우에", listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("い", 54, 144),
                AppleVisualSourceCell("うえ", 54, 144, nativeLineCount = 2),
            ), emptyList(),
        ))
        // A wrapped cell before the terminal row also remains unsupported.
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あいう", "아이우", listOf(
                AppleVisualSourceCell("あ", 0, 95, nativeLineCount = 2),
                AppleVisualSourceCell("い", 106, 146),
                AppleVisualSourceCell("う", 158, 198),
            ), emptyList(),
        ))
        // A wrapped final cell made only of punctuation cannot own a
        // pronunciation row; neither can an earlier punctuation-only row.
        assertNull(AppleInterleavedReadingPolicy.plan(
            "あい!", "아이", listOf(
                AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("い", 52, 92),
                AppleVisualSourceCell("!", 104, 145, nativeLineCount = 2),
            ), emptyList(),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            "!あ", "아", listOf(
                AppleVisualSourceCell("!", 0, 40),
                AppleVisualSourceCell("あ", 52, 144, nativeLineCount = 2),
            ), emptyList(),
        ))
    }

    @Test fun multipleNativeChildrenWithInternalWrapsNeedPeerAwareEvidence() {
        // Two flex rows have four native cells. A wrapped TextView shares
        // each row with another child: inserting between TextView soft lines
        // is NOT equivalent to adding a full-width flex child afterward.
        val evidence = requireNotNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 70, listOf(AppleNativeWrapWordLineShape(2, 0))),
            AppleNativeWrapCellShape(0, 70, listOf(
                AppleNativeWrapWordLineShape(1, 0), AppleNativeWrapWordLineShape(1, 0),
            )),
            AppleNativeWrapCellShape(80, 145, listOf(AppleNativeWrapWordLineShape(3, 0))),
            AppleNativeWrapCellShape(80, 145, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertEquals(4, evidence.sourceCells)
        assertEquals(5, evidence.sourceWords)
        assertEquals(2, evidence.flexRows)
        assertEquals(2, evidence.wrappedSourceChildren)
        assertEquals(2, evidence.wrappedWithPeers)
        assertEquals(2, evidence.nativeWrappedCells)
        assertEquals(3, evidence.additionalNativeSoftLines)
        assertEquals(0, evidence.nativeHardBreakLines)
        assertEquals(0, evidence.wrappedMultiWordChildren)
        // A wrapped child alone on its flex row must be distinguished from
        // one that would share the newly reserved source-line spacing.
        val isolated = requireNotNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 90, listOf(
                AppleNativeWrapWordLineShape(2, 0), AppleNativeWrapWordLineShape(1, 0),
            )),
            AppleNativeWrapCellShape(101, 145, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertEquals(0, isolated.wrappedWithPeers)
        assertEquals(1, isolated.wrappedMultiWordChildren)
        // A deliberate newline in a native word is NOT evidence of a soft
        // wrap, even if Layout.lineCount reports two physical lines. It must
        // still be reported so the final device trace can tell WHY a native
        // row has multiple Layout lines without inventing a split.
        val hardOnly = requireNotNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 90, listOf(AppleNativeWrapWordLineShape(2, 1))),
            AppleNativeWrapCellShape(101, 145, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertEquals(0, hardOnly.wrappedSourceChildren)
        assertEquals(0, hardOnly.additionalNativeSoftLines)
        assertEquals(1, hardOnly.nativeHardBreakLines)
        val mixedBreaks = requireNotNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 90, listOf(AppleNativeWrapWordLineShape(3, 1))),
            AppleNativeWrapCellShape(101, 145, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertEquals(1, mixedBreaks.nativeHardBreakLines)
        assertEquals(1, mixedBreaks.additionalNativeSoftLines)
        assertNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 60, listOf(AppleNativeWrapWordLineShape(1, 0))),
            AppleNativeWrapCellShape(65, 100, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 0, listOf(AppleNativeWrapWordLineShape(2, 0))),
            AppleNativeWrapCellShape(65, 100, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 60, listOf(
                AppleNativeWrapWordLineShape(0, 0), AppleNativeWrapWordLineShape(2, 0),
            )),
            AppleNativeWrapCellShape(65, 100, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
        assertNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(0, 60, emptyList()),
            AppleNativeWrapCellShape(65, 100, listOf(AppleNativeWrapWordLineShape(2, 0))),
        )))
        // A source child entirely ABOVE an earlier child contradicts the
        // visual-order assumption used for peer-row classification.
        assertNull(AppleNativeMultiWrapGeometryPolicy.summarize(listOf(
            AppleNativeWrapCellShape(80, 145, listOf(AppleNativeWrapWordLineShape(2, 0))),
            AppleNativeWrapCellShape(0, 70, listOf(AppleNativeWrapWordLineShape(1, 0))),
        )))
    }

    @Test fun settledMultiChildFallbackMustBeTracedEvenWhenReadingViewCanBeReused() {
        val policy = AppleNativeMultiWrapGeometryPolicy
        // Probe eligibility must depend on *measured native geometry*, not
        // whether this pass inserts a new ReadingView or reuses one.
        assertEquals(true, policy.shouldTrace(
            sourceCells = 4, hasMultilineNativeWord = true, geometryPending = false,
            interleavedRowsReady = false, inlineSoftWrapReady = false,
        ))
        assertEquals(false, policy.shouldTrace(
            sourceCells = 4, hasMultilineNativeWord = true, geometryPending = true,
            interleavedRowsReady = false, inlineSoftWrapReady = false,
        ))
        assertEquals(false, policy.shouldTrace(
            sourceCells = 1, hasMultilineNativeWord = true, geometryPending = false,
            interleavedRowsReady = false, inlineSoftWrapReady = false,
        ))
        assertEquals(false, policy.shouldTrace(
            sourceCells = 4, hasMultilineNativeWord = false, geometryPending = false,
            interleavedRowsReady = false, inlineSoftWrapReady = false,
        ))
        assertEquals(false, policy.shouldTrace(
            sourceCells = 4, hasMultilineNativeWord = true, geometryPending = false,
            interleavedRowsReady = true, inlineSoftWrapReady = false,
        ))
    }

    @Test fun nativeLayoutLineEndDistinguishesExplicitNewlineFromSoftWrap() {
        val policy = AppleNativeMultiWrapGeometryPolicy
        assertEquals(true, policy.isHardBreakAtLineEnd("あ\nい", 2))
        assertEquals(false, policy.isHardBreakAtLineEnd("あい", 1))
        assertEquals(true, policy.isHardBreakAtLineEnd("𠮟\nい", 3))
        assertEquals(true, policy.isHardBreakAtLineEnd("あ\r\nい", 3))
        assertEquals(true, policy.isHardBreakAtLineEnd("あ\u2028い", 2))
        assertEquals(false, policy.isHardBreakAtLineEnd("あ\nい", 0))
        assertEquals(false, policy.isHardBreakAtLineEnd("あ\nい", 5))
    }

    @Test fun `supplementary Han source cannot masquerade as an all kana lyric`() {
        val source = "𠮟かな"
        assertEquals(false, AppleInterleavedReadingPolicy.canDeriveKanaRows(source, "카나"))
        assertNull(AppleInterleavedReadingPolicy.verifiedKanaWords(
            source, listOf("𠮟", "かな"), "카나",
        ))
    }

    @Test fun `verified supplementary Han keeps its whole UTF16 code point and reading row`() {
        val result = requireNotNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "𠮟あ",
            pronunciation = "시츠 아",
            cells = listOf(
                AppleVisualSourceCell("𠮟", 0, 40),
                AppleVisualSourceCell("あ", 51, 91),
            ),
            spans = listOf(
                ApplePronunciationSourceToken(0, 2, "시츠", "𠮟"),
                ApplePronunciationSourceToken(2, 3, "아", "あ"),
            ),
        ))
        assertEquals(listOf(0, 1), result.map { it.insertAfterSourceCell })
        assertEquals(listOf("시츠", "아"), result.map { it.text })
    }

    @Test fun `one supplementary Han with a verified kana suffix can own the residual sound`() {
        assertEquals(listOf("시츠", "아"),
            AppleInterleavedReadingPolicy.verifiedFragmentSounds(
                listOf("𠮟", "あ"), "시츠아",
            ))
    }

    @Test fun `UTF16 surrogate halves are never independent visual or phonetic rows`() {
        assertNull(AppleNativeSoftWrapReadingPolicy.plan(
            sourceText = "𠮟あ", displayedText = "𠮟あ", pronunciation = "시츠아",
            visualLineRanges = listOf(0 until 1, 1 until 3),
            spans = listOf(
                ApplePronunciationSourceToken(0, 2, "시츠", "𠮟"),
                ApplePronunciationSourceToken(2, 3, "아", "あ"),
            ),
        ))
        assertNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "𠮟あ", pronunciation = "시츠아",
            cells = listOf(AppleVisualSourceCell("𠮟", 0, 40),
                AppleVisualSourceCell("あ", 51, 91)),
            spans = listOf(
                ApplePronunciationSourceToken(0, 1, "시", "\uD842"),
                ApplePronunciationSourceToken(1, 2, "츠", "\uDF9F"),
                ApplePronunciationSourceToken(2, 3, "아", "あ"),
            ),
        ))
    }

    @Test fun `orphan Hangul jamo cannot be assigned to a measured Japanese row`() {
        assertNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "あい", pronunciation = "아ㄴ",
            cells = listOf(AppleVisualSourceCell("あ", 0, 40),
                AppleVisualSourceCell("い", 51, 91)),
            spans = listOf(
                ApplePronunciationSourceToken(0, 1, "아", "あ"),
                ApplePronunciationSourceToken(1, 2, "ㄴ", "い"),
            ),
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

    @Test fun `native timed cells and measured wrapped rows use identical phonetic proof`() {
        val cases = listOf(
            Triple("脳みそ", listOf("脳", "みそ"), "노미소"),
            Triple("喚く", listOf("喚", "く"), "와메쿠"),
            Triple("溺れ", listOf("溺", "れ"), "오보레"),
        )
        cases.forEach { (source, fragments, full) ->
            val native = AppleNativeReadingProjection.project(
                source, fragments,
                listOf(SourceReadingSpan(0, source.length, source, source, full)), full,
            )
            val cells = fragments.mapIndexed { index, surface ->
                AppleVisualSourceCell(surface, index * 50, index * 50 + 40)
            }
            val rows = AppleInterleavedReadingPolicy.plan(
                source, full, cells,
                listOf(ApplePronunciationSourceToken(0, source.length, full, source)),
            )
            val actualRows = requireNotNull(rows) { "$source must create verified visual rows" }
            val actualNative = requireNotNull(native) { "$source must use native clocks" }
            assertEquals("$source source-to-sound boundaries diverged",
                actualRows.map { it.text }, actualNative.words.map { it.hangul })
        }
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

    @Test fun `incomplete offline kana spans cannot suppress independently verified visual rows`() {
        val rows = requireNotNull(AppleInterleavedReadingPolicy.plan(
            sourceText = "かなかな", pronunciation = "카나카나",
            cells = listOf(
                AppleVisualSourceCell("かな", 0, 40),
                AppleVisualSourceCell("かな", 52, 92),
            ),
            // A partial dictionary map is not a complete row proof, but the
            // literal full-kana converter independently proves both rows.
            spans = listOf(ApplePronunciationSourceToken(0, 2, "카나", "かな")),
        ))
        assertEquals(listOf("카나", "카나"), rows.map { it.text })
        assertEquals(listOf(0, 1), rows.map { it.insertAfterSourceCell })
    }

    @Test fun `only complete mixed source anchors warrant deferred measured row insertion`() {
        val partial = listOf(ApplePronunciationSourceToken(0, 2, "다메", "駄目"))
        assertEquals(false, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "駄目かな", "다메카나", partial,
        ))
        assertEquals(true, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "駄目かな", "다메카나", partial +
                ApplePronunciationSourceToken(2, 4, "카나", "かな"),
        ))
        assertEquals(true, AppleInterleavedReadingPolicy.hasPotentialRowProjection(
            "かなかな", "카나카나", listOf(
                ApplePronunciationSourceToken(0, 2, "카나", "かな"),
            ),
        ))
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

    @Test fun `verified kanji wrap carries one original word sweep across reading rows`() {
        // Both fragments derive from the same fully verified 脳みそ pronunciation.
        // They share the unmodified native word window; only the measured
        // glyph geometry decides which portion of its sweep each row paints.
        val first = AppleInterleavedReadingRow(0, "노", 0, 1)
        val second = AppleInterleavedReadingRow(1, "미소", 1, 3)
        val original = AppleTimedReadingUnit(0, 3, 1000L, 2000L, sourceWordIndex = 0)
        val rows = listOf(first, second)
        assertEquals(listOf(original.copy(
            end = 1, sweepFullReading = "노미소", sweepStartOffset = 0,
        )), AppleInterleavedReadingTimingPolicy.project(
            listOf(listOf(original)), "노미소", rows, first, verifiedCrossRow = true,
        ).single())
        assertEquals(listOf(original.copy(
            start = 0, end = 2, sweepFullReading = "노미소", sweepStartOffset = 1,
        )), AppleInterleavedReadingTimingPolicy.project(
            listOf(listOf(original)), "노미소", rows, second, verifiedCrossRow = true,
        ).single())
    }

    @Test fun `verified row split never fabricates a fine clock from a whole line interval`() {
        val rows = listOf(
            AppleInterleavedReadingRow(0, "노", 0, 1),
            AppleInterleavedReadingRow(1, "미소", 1, 3),
        )
        val coarse = AppleTimedReadingUnit(0, 3, 1000L, 2000L, isWholeLine = true)
        rows.forEach { row ->
            assertEquals(listOf(emptyList<AppleTimedReadingUnit>()),
                AppleInterleavedReadingTimingPolicy.project(
                    listOf(listOf(coarse)), "노미소", rows, row,
                    verifiedCrossRow = true,
                ))
        }
    }
}
