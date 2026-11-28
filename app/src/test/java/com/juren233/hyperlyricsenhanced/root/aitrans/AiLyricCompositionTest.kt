package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLyricCompositionTest {
    @Test fun invalidLegacyMixedLocalReadingCannotOverwriteFreshRemoteCorrection() {
        val source = listOf("さよなら 사랑해")
        val remote = listOf(TranslationItem(0, "안녕 사랑해", "사요나라 사랑해"))
        val local = listOf(TranslationItem(0, roma = "사요나라"))
        val validLocal = AiLyricComposition.validatedLocalReadings(source, local, false)
        assertTrue(validLocal.isEmpty())
        assertEquals("사요나라 사랑해", AiLyricComposition.merge(remote, validLocal).single().roma)
    }

    @Test fun phoneticsLocalCacheSkipsChineseRowsAndKeepsJapaneseRow() {
        val source = listOf("感谢你曾来过", "君の名は")
        val cache = listOf(TranslationItem(0, roma = "칸샤니"),
            TranslationItem(1, roma = "키미노 나와"))
        assertEquals(listOf(1), AiLyricComposition.validatedLocalReadings(
            source, cache, chineseDominant = true,
        ).map { it.index })
    }
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

    @Test fun preservesNaturalAiSpacingWhenItsSoundsMatchTheLocalDictionary() {
        val translated = listOf(
            TranslationItem(index = 0, trans = "머릿속에서", roma = "노미소노 나카카라"),
        )
        val local = listOf(TranslationItem(index = 0, roma = "노미소노나카카라"))
        assertEquals(
            "노미소노 나카카라",
            AiLyricComposition.merge(translated, local).single().roma,
        )
    }

    @Test fun ignoresUnverifiedAiLettersEvenWhenTheMainSyllablesLookTheSame() {
        val translated = listOf(TranslationItem(index = 0, roma = "다메ㅋㅋ"))
        val local = listOf(TranslationItem(index = 0, roma = "다메"))
        assertEquals("다메", AiLyricComposition.merge(translated, local).single().roma)
    }
}
