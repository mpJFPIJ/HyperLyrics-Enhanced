package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.*
import org.junit.Test

class KoreanLyricOutputPolicyTest {
    @Test fun rejectsForeignLanguageAndSparseKoreanOutput() {
        assertFalse(KoreanLyricOutputPolicy.accepts(
            listOf(TranslationItem(0, "你好"), TranslationItem(1, "你的名字")),
            setOf(0, 1), "한국어"))
        assertFalse(KoreanLyricOutputPolicy.accepts(
            listOf(TranslationItem(0, "안녕")), (0..9).toSet(), "한국어"))
        assertTrue(KoreanLyricOutputPolicy.accepts(
            listOf(TranslationItem(0, "너의 이름은"), TranslationItem(1, "사랑해")),
            setOf(0, 1), "한국어"))
        assertTrue(KoreanLyricOutputPolicy.accepts(
            listOf(TranslationItem(0, "你好")), setOf(0), "中文"))
    }

    @Test fun repairsPhoneticsWithoutRewritingTranslations() {
        val initial = listOf(
            TranslationItem(0, "너의 이름은"),
            TranslationItem(1, "사랑해", "아이 러브 유"),
            TranslationItem(2, "고마워", "romaji"),
        )
        assertTrue(KoreanLyricOutputPolicy.needsPronunciationRepair(initial, 8))
        val result = KoreanLyricOutputPolicy.mergePhonetics(initial, listOf(
            TranslationItem(0, "", "키미노 나와"),
            TranslationItem(1, "", "아이 라브 유"),
            TranslationItem(2, "", "아리가토"),
            TranslationItem(3, "", "추가 발음"),
            TranslationItem(4, "", "romaji"),
        ))
        assertEquals("너의 이름은", result[0].trans)
        assertEquals("키미노 나와", result[0].roma)
        assertEquals("아이 러브 유", result[1].roma)
        assertEquals("아리가토", result[2].roma)
        assertEquals("추가 발음", result.single { it.index == 3 }.roma)
        assertFalse(result.any { it.index == 4 })
    }
}
