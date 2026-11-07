package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JapaneseToHangulTest {
    @Test fun basicAndLongVowelReadings() {
        assertEquals("다메", JapaneseToHangul.fromKana("ダメ"))
        assertEquals("노미소", JapaneseToHangul.fromKana("ノウミソ"))
        assertEquals("카와이이", JapaneseToHangul.fromKana("カワイイ"))
        assertEquals("아리가토", JapaneseToHangul.fromKana("アリガトウ"))
    }

    @Test fun handlesParticlesDigraphAndNasalCoda() {
        assertEquals("키미노 나와", JapaneseToHangul.fromKana("キミノ ナワ"))
        assertEquals("슈칸", JapaneseToHangul.fromKana("シュカン"))
        assertEquals("얏타", JapaneseToHangul.fromKana("ヤッタ"))
    }

    @Test fun refusesUnknownKanjiRatherThanInventingReading() {
        assertNull(JapaneseToHangul.fromKana("愛してる"))
    }

    @Test fun repeatedWordsStayDistinct() {
        assertEquals("다메 다메", JapaneseToHangul.fromKana("ダメ ダメ"))
    }
}
