package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalJapanesePronunciationTest {
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
