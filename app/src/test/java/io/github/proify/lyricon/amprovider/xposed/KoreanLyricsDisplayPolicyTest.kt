package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class KoreanLyricsDisplayPolicyTest {
    @Test fun preferHangulOverlayInsteadOfChineseOfficial() {
        assertEquals("너만 있으면 돼", KoreanLyricsDisplayPolicy.select(
            userCorrection = null, supplement = "너만 있으면 돼", official = "只要有你"))
    }

    @Test fun doNotDisplayForeignTranslationWhenNoKoreanExists() {
        assertNull(KoreanLyricsDisplayPolicy.select(null, "我爱你", "君が好き"))
        assertNull(KoreanLyricsDisplayPolicy.select(null, null, "只有你"))
        assertNull(KoreanLyricsDisplayPolicy.select(null, "kimiwa", null))
    }

    @Test fun keepKoreanOfficialAndExplicitUserCorrections() {
        assertEquals("고마워", KoreanLyricsDisplayPolicy.select(null, null, "고마워"))
        assertEquals("사용자 수정", KoreanLyricsDisplayPolicy.select(
            "사용자 수정", "다른 번역", "원래 번역"))
        assertEquals("!", KoreanLyricsDisplayPolicy.select(null, "!", "我爱你"))
        assertNull(KoreanLyricsDisplayPolicy.select(null, "// //", "我爱你"))
    }
}
