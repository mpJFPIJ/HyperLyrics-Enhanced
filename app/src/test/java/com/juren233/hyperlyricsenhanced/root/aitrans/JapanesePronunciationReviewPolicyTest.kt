package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapanesePronunciationReviewPolicyTest {
    @Test fun reviewsOnlyJapaneseKanjiLinesAndPreservesOriginalIndices() {
        val lyrics = listOf(
            TranslationRequestItem(3, "君の名は"),
            TranslationRequestItem(7, "駄目"),
            TranslationRequestItem(9, "ありがとう"),
            TranslationRequestItem(11, "I love you"),
        )
        val selected = JapanesePronunciationReviewPolicy.candidates(lyrics)
        assertEquals(listOf(3, 7), selected.map { it.index })
        assertTrue(JapanesePronunciationReviewPolicy.candidates(
            listOf(TranslationRequestItem(12, "你好"), TranslationRequestItem(13, "世界")),
        ).isEmpty())
    }

    @Test fun acceptsConservativeReadingCorrectionsWithoutChangingMeaning() {
        val existing = listOf(
            TranslationItem(3, "안 돼", "다무"),
            TranslationItem(7, "너의 이름은", "키미노 나와"),
        )
        val reviewed = listOf(
            TranslationItem(3, "조작된 번역", "다메"),
            TranslationItem(7, "가짜 번역", "kimi no na wa"),
            TranslationItem(88, "추가", "잘못된"),
        )
        val merged = JapanesePronunciationReviewPolicy.merge(
            existing, reviewed, setOf(3, 7), mapOf(3 to "駄目", 7 to "君の名は"),
        )
        assertEquals("안 돼", merged[0].trans)
        assertEquals("다메", merged[0].roma)
        assertEquals("너의 이름은", merged[1].trans)
        assertEquals("키미노 나와", merged[1].roma)
        assertFalse(merged.any { it.index == 88 })
    }

    @Test fun rejectsAnImplausiblyLongOrNonJapaneseReview() {
        val existing = listOf(TranslationItem(1, "고마워", "아리가토"))
        val merged = JapanesePronunciationReviewPolicy.merge(
            existing,
            listOf(TranslationItem(1, "", "엄청길고상관없는한글발음을무한히늘여서붙인결과입니다")),
            setOf(1), mapOf(1 to "ありがとう"),
        )
        assertTrue(merged === existing || merged == existing)
    }
}
