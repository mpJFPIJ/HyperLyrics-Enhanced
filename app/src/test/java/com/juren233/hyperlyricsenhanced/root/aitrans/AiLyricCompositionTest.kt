package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLyricCompositionTest {
    @Test fun sourceDerivedPronunciationOverridesUnverifiedAiHallucinations() {
        val translated = listOf(
            TranslationItem(index = 0, trans = "안 돼", roma = "다메 키코 노미소"),
            TranslationItem(index = 2, trans = "너의 이름은"),
        )
        val local = listOf(
            TranslationItem(index = 0, roma = "다메 노미소"),
            TranslationItem(index = 2, roma = "키미노 나와"),
            TranslationItem(index = 3, roma = "아이 러브 유"),
        )
        val merged = AiLyricComposition.merge(translated, local)
        assertEquals(listOf(0, 2, 3), merged.map { it.index })
        assertEquals("안 돼", merged[0].trans)
        assertEquals("다메 노미소", merged[0].roma)
        assertEquals("키미노 나와", merged[1].roma)
        assertEquals("", merged[2].trans)
    }

    @Test fun invalidAiPronunciationUsesOfflineHangulFallback() {
        val translated = listOf(TranslationItem(0, "싫어", "dame"))
        val local = listOf(TranslationItem(0, roma = "다메"))
        assertEquals("다메", AiLyricComposition.merge(translated, local).single().roma)
    }

    @Test fun keepsExistingReadingsWhereLocalAnalysisIsNotAvailable() {
        val translated = listOf(TranslationItem(index = 0, trans = "사랑해", roma = "러브"))
        assertEquals(translated, AiLyricComposition.merge(translated, emptyList()))
    }
}
