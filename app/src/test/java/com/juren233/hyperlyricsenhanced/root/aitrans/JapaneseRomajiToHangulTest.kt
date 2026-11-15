package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JapaneseRomajiToHangulTest {
    @Test fun appleLatinJapaneseBecomesHangulWithoutChangingOriginalWords() {
        assertEquals("키미 노 나 와", JapaneseRomajiToHangul.convert("Kimi no na wa"))
        assertEquals("아리가토", JapaneseRomajiToHangul.convert("arigato"))
        assertEquals("칸지", JapaneseRomajiToHangul.convert("kanji"))
        assertEquals("콘니치와", JapaneseRomajiToHangul.convert("Konnichiwa"))
    }
    @Test fun normalizesMacronsAndRefusesNonJapaneseSymbols() {
        assertEquals("토쿄", JapaneseRomajiToHangul.convert("Tōkyō"))
        assertNull(JapaneseRomajiToHangul.convert("完全な歌詞"))
    }
}
