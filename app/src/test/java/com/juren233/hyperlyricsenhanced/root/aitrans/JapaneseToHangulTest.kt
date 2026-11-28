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

    @Test fun halfwidthKanaFoldsVoicingAndDigraphsWithoutChangingOtherScripts() {
        assertEquals("하로", JapaneseToHangul.fromKana("ﾊﾛｰ"))
        assertEquals("가", JapaneseToHangul.fromKana("ｶﾞ"))
        assertEquals("파", JapaneseToHangul.fromKana("ﾊﾟ"))
        assertEquals("캬", JapaneseToHangul.fromKana("ｷｬ"))
        assertEquals("가 하로", JapaneseToHangul.fromKana("ｶﾞ･ﾊﾛｰ"))
        assertEquals("하로", JapaneseToHangul.fromKana("ハロー"))
        // Global NFKC would also fold unrelated compatibility letters.
        assertNull(JapaneseToHangul.fromKana("Ａ"))
        assertNull(JapaneseToHangul.fromKana("ｶﾞ愛"))
    }

    @Test fun decomposedVoicingIsComposedOnlyForTranscriptionNotForSourceOffsets() {
        assertEquals("가", JapaneseToHangul.fromKana("が"))
        assertEquals("가", JapaneseToHangul.fromKana("か\u3099"))
        assertEquals("가", JapaneseToHangul.fromKana("カ\u3099"))
        assertEquals("가", JapaneseToHangul.fromKana("カ\uFF9E"))
        assertEquals("파", JapaneseToHangul.fromKana("は\u309A"))
        assertNull(JapaneseToHangul.fromKana("\u3099か"))
        assertNull(JapaneseToHangul.fromKana("か\u3099愛"))
        assertNull(JapaneseToHangul.fromKana("Ａ\u3099"))
    }
}
