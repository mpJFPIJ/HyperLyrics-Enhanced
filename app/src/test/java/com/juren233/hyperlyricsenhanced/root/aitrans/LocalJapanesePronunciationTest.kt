package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalJapanesePronunciationTest {

    @Test fun kanaOnlyFallsBackWhenSudachiFails() {
        val failed = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        val ordinary = LocalJapanesePronunciation.generateLine("ありがとう") { failed }
        assertEquals(LineReadingStatus.COMPLETE, ordinary.status)
        assertEquals("아리가토", ordinary.text)
        assertTrue(ordinary.spans.isNotEmpty())
        assertEquals("콘니치와",
            LocalJapanesePronunciation.generateLine("こんにちは") { failed }.text)
        assertNull(LocalJapanesePronunciation.generateLine("未知語") { failed }.text)
    }

    @Test fun dictionaryFailureCannotGuessTheGrammarOfHiraganaHaOrHe() {
        val failed = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        for (source in listOf("きみへ", "きみは", "きみ へ！", "こんばんは", "はる", "へや")) {
            val result = LocalJapanesePronunciation.generateLine(source) { failed }
            assertEquals("Without morphology $source has an ambiguous は/へ", failed.status, result.status)
            assertNull(result.text)
        }
        // A narrowly recognized lexical greeting has an independently known
        // pronunciation; unambiguous kana and Katakana remain available.
        assertEquals("콘니치와", LocalJapanesePronunciation.generateLine("こんにちは！") { failed }.text)
        assertEquals("키미", LocalJapanesePronunciation.generateLine("きみ") { failed }.text)
        assertEquals("하로", LocalJapanesePronunciation.generateLine("ハロー") { failed }.text)

        val context = SourceReadingAssembler.assemble("きみへ", listOf(
            RawReadingToken(0, 2, "キミ"), RawReadingToken(2, 3, "エ", isParticle = true),
        ))
        assertEquals(LineReadingStatus.COMPLETE, context.status)
        assertEquals("키미에", LocalJapanesePronunciation.generateLine("きみへ") { context }.text)
    }

    @Test fun halfwidthKanaFallbackPreservesTheOriginalSourceAndUtf16Offsets() {
        val source = "ﾊﾛｰ ｶﾞ!"
        val observed = mutableListOf<String>()
        val failed = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        val result = LocalJapanesePronunciation.generateLine(source) { original ->
            observed += original
            failed
        }
        assertEquals(listOf(source), observed)
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("하로 가", result.text)
        assertEquals(listOf("ﾊﾛｰ", "ｶﾞ"), result.spans.map { it.sourceText })
        assertEquals(listOf(0 to 3, 4 to 6),
            result.spans.map { it.sourceStart to it.sourceEnd })
    }

    @Test fun halfwidthKanaPunctuationSeparatesFallbackWordsWithoutInventingKanji() {
        val failed = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        assertEquals("가 하로",
            LocalJapanesePronunciation.generateLine("ｶﾞ･ﾊﾛｰ") { failed }.text)
        val unreadable = LocalJapanesePronunciation.generateLine("ｶﾞ未知") { failed }
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, unreadable.status)
        assertNull(unreadable.text)
    }

    @Test fun `kana-only fallback cannot silently skip supplementary Han when dictionary fails`() {
        val failed = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        val mixed = LocalJapanesePronunciation.generateLine("𠮟かな") { failed }
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, mixed.status)
        assertNull(mixed.text)
        assertTrue(mixed.spans.isEmpty())
        val prefixed = LocalJapanesePronunciation.generateLine("かな𠮟") { failed }
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, prefixed.status)
        assertNull(prefixed.text)
    }

    @Test fun `supplementary Han is passed intact to the full-line Japanese analyzer`() {
        val observed = mutableListOf<String>()
        val source = "𠮟"
        val result = LocalJapanesePronunciation.generateLine(source) { whole ->
            observed += whole
            LineReadingResult(
                text = "시카",
                spans = listOf(SourceReadingSpan(0, source.length, source, "シカ", "시카")),
                status = LineReadingStatus.COMPLETE,
            )
        }
        assertEquals(listOf(source), observed)
        assertEquals(LineReadingStatus.COMPLETE, result.status)
        assertEquals("시카", result.text)
        assertEquals(0 to 2, result.spans.single().let { it.sourceStart to it.sourceEnd })
    }

    @Test fun `legacy Japanese reading does not drop supplementary Han beside English words`() {
        val analyzer: (String) -> String? = { when (it) {
            "𠮟" -> "シカ"
            else -> null
        } }
        assertEquals("시카", LocalJapanesePronunciation.generate(listOf("𠮟"), analyzer)
            .single().roma)
        assertEquals("시카 아이 러브 유", LocalJapanesePronunciation.generate(
            listOf("𠮟 I love you"), analyzer,
        ).single().roma)
        // If the source glyph is unknown, the English words may not be
        // presented as a complete pronunciation of this Japanese lyric.
        assertTrue(LocalJapanesePronunciation.generate(
            listOf("𠮟 I love you"), { null },
        ).isEmpty())
    }

    @Test fun canonicalReadingAnalyzesJapaneseOriginalOnceNotEachWord() {
        val source = "駄目駄目脳みその中から"
        val observed = mutableListOf<String>()
        val parsed = LocalJapanesePronunciation.generateLine(source) { whole ->
            observed += whole
            SourceReadingAssembler.assemble(whole, listOf(
                RawReadingToken(0, 2, "ダメ"),
                RawReadingToken(2, 4, "ダメ"),
                RawReadingToken(4, 5, "ノウ"),
                RawReadingToken(5, 7, "ミソ"),
                RawReadingToken(7, 8, "ノ", isParticle = true),
                RawReadingToken(8, 9, "ナカ"),
                RawReadingToken(9, 11, "カラ"),
            ))
        }
        assertEquals(listOf(source), observed)
        assertEquals(LineReadingStatus.COMPLETE, parsed.status)
        assertEquals("다메다메노미소노나카카라", parsed.text)
    }

    @Test fun alignedWordsPreservesPunctuationSlotsAndFailsClosedOnUnknownJapaneseWords() {
        val analyzer: (String) -> String? = { when (it) {
            "駄目" -> "ダメ"
            else -> null
        } }
        val result = LocalJapanesePronunciation.alignedWords(
            words = listOf("駄目", "「", "駄目", "」"),
            linePronunciation = "다메 다메",
            japaneseReading = analyzer,
        )
        assertEquals(listOf("다메", "", "다메", ""), result?.map { it.hangul })
        assertNull(LocalJapanesePronunciation.alignedWords(
            words = listOf("駄目", "未知読"),
            linePronunciation = "다메 키코",
            japaneseReading = analyzer,
        ))
    }

    @Test fun keepInputIndicesAndDoNotInventUnknownReadings() {
        val source = listOf("駄目 駄目", "", "ありがとう", "未知読", "I love you")
        val fakeAnalyzer: (String) -> String? = { text -> when (text) {
            "駄目 駄目" -> "ダメ ダメ"
            "ありがとう" -> "アリガトウ"
            else -> null
        } }
        val result = LocalJapanesePronunciation.generate(source, fakeAnalyzer)
        assertEquals(listOf(0, 2, 4), result.map { it.index })
        assertEquals("다메 다메", result[0].roma)
        assertEquals("아리가토", result[1].roma)
        assertEquals("아이 러브 유", result[2].roma)
        assertTrue(result.all { it.trans.isBlank() })
    }

    @Test fun handlesEnglishWordsEmbeddedInJapaneseLyrics() {
        val result = LocalJapanesePronunciation.generate(
            listOf("君の名は I love you"),
        ) { if (it == "君の名は") "キミノナワ" else null }
        assertEquals("키미노나와 아이 러브 유", result.single().roma)
    }

    @Test fun alignsActualKaraokeWordReadingsOnlyWhenTheyAgreeWithTheSelectedLine() {
        val analyzer: (String) -> String? = { when (it) {
            "駄目" -> "ダメ"
            "脳" -> "ノウ"
            "みそ" -> "ミソ"
            else -> null
        } }
        val aligned = LocalJapanesePronunciation.alignedWords(
            listOf("駄目", "駄目", "脳", "みそ"),
            linePronunciation = "다메 다메 노미소",
            japaneseReading = analyzer,
        )
        assertEquals(listOf("다메", "다메", "노", "미소"), aligned?.map { it.hangul })
        assertNull(LocalJapanesePronunciation.alignedWords(
            listOf("駄目", "駄目", "脳", "みそ"),
            linePronunciation = "다메 다메 키코",
            japaneseReading = analyzer,
        ))
    }
}
