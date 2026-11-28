package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.*
import org.junit.Test

class KoreanLyricOutputPolicyTest {
    @Test fun repairsMissingPronunciationEvenWhenSomeLinesHaveHangulReadings() {
        val onePresent = listOf(
            TranslationItem(0, "번역", "아리가토"),
            TranslationItem(1, "번역"),
            TranslationItem(2, "번역"),
            TranslationItem(3, "번역"),
        )
        assertTrue(KoreanLyricOutputPolicy.needsPronunciationRepair(onePresent, 4))
        val threePresent = onePresent.mapIndexed { index, item ->
            if (index in 1..2) item.copy(roma = "키미노 나와") else item
        }
        assertTrue(KoreanLyricOutputPolicy.needsPronunciationRepair(threePresent, 4))
        assertFalse(KoreanLyricOutputPolicy.needsPronunciationRepair(
            threePresent.map { it.copy(roma = "키미노 나와") }, 4,
        ))
    }

    @Test fun replacingInvalidHangulReadingOfMixedOriginalUsesSourceValidation() {
        val initial = listOf(TranslationItem(0, "안녕 사랑해", "사요나라"))
        val originals = mapOf(0 to "さよなら 사랑해")
        val repaired = KoreanLyricOutputPolicy.mergePhonetics(
            initial, listOf(TranslationItem(0, "", "사요나라 사랑해")), originals,
        )
        assertEquals("사요나라 사랑해", repaired.single().roma)
        val rejected = KoreanLyricOutputPolicy.mergePhonetics(
            initial, listOf(TranslationItem(0, "", "사요나라")), originals,
        )
        assertEquals("사요나라", rejected.single().roma)
    }

    @Test fun acceptsNaturalKoreanTranslationWithEmbeddedEnglishWords() {
        val items = listOf(
            TranslationItem(0, "너를 향한 LOVE"),
            TranslationItem(1, "오늘도 너와 함께"),
            TranslationItem(2, "끝나지 않는 NIGHT"),
            TranslationItem(3, "우리만의 SECRET"),
        )
        assertTrue(KoreanLyricOutputPolicy.acceptsComplete(items, (0..3).toSet(), "한국어"))
    }

    @Test fun rejectsForeignDominatedTranslationDespiteAFragmentOfHangul() {
        val items = listOf(
            TranslationItem(0, "你好 世界 사랑"),
            TranslationItem(1, "君の名は"),
            TranslationItem(2, "世界へ"),
            TranslationItem(3, "LOVE"),
        )
        assertFalse(KoreanLyricOutputPolicy.acceptsComplete(items, (0..3).toSet(), "ko"))
    }

    @Test fun supplementaryHanAloneIsNotMistakenForPunctuationOnlyTranslation() {
        assertFalse(KoreanLyricOutputPolicy.acceptsComplete(
            listOf(TranslationItem(0, "𠀀")), setOf(0), "한국어",
        ))
    }

    @Test fun oneKoreanSyllableDoesNotValidateAnEnglishSentence() {
        assertFalse(KoreanLyricOutputPolicy.acceptsComplete(
            listOf(TranslationItem(0, "I will always love you forever 요")), setOf(0), "한국어",
        ))
    }

    @Test fun doesNotAcceptPartialOrForeignLanguageFallbackForCaching() {
        val expected = setOf(0, 1, 2)
        assertFalse(KoreanLyricOutputPolicy.acceptsComplete(
            listOf(TranslationItem(0, "안녕"), TranslationItem(1, "사랑해")),
            expected, "한국어",
        ))
        assertFalse(KoreanLyricOutputPolicy.acceptsComplete(
            listOf(TranslationItem(0, "안녕"), TranslationItem(1, "你好"), TranslationItem(2, "世界")),
            expected, "한국어",
        ))
        assertTrue(KoreanLyricOutputPolicy.acceptsComplete(
            listOf(TranslationItem(0, "안녕"), TranslationItem(1, "사랑해"), TranslationItem(2, "고마워")),
            expected, "한국어",
        ))
    }

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
