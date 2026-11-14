package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeReadingProjectionTest {
    @Test fun groupedJapaneseMorphemeCanFollowNativeWordClock() {
        val result = AppleNativeReadingProjection.project(
            "駄目脳みそ", listOf("駄", "目", "脳", "みそ"),
            listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 3, "脳", "ノウ", "노"),
                SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
            ), "다메노미소",
        )
        assertTrue(result?.approximate == true)
        assertEquals(listOf("다", "메", "노", "미소"), result?.words?.map { it.hangul })
    }
    @Test fun neverProjectsUnverifiedSourceSound() {
        assertNull(AppleNativeReadingProjection.project(
            "君の名は", listOf("君", "の", "名は"),
            listOf(SourceReadingSpan(0, 1, "君", "キミ", "키미")),
            "키미노 나와",
        ))
    }
    @Test fun insufficientPhoneticSyllablesDoNotFakeOneForEveryAppleCell() {
        assertNull(AppleNativeReadingProjection.project(
            "ダーリン", listOf("ダ", "ー", "リ", "ン"),
            listOf(SourceReadingSpan(0, 4, "ダーリン", "ダーリン", "다린")),
            "다린",
        ))
    }
    @Test fun unchangedCompleteReadingIsNotMarkedEstimated() {
        val result = AppleNativeReadingProjection.project(
            "君の名は", listOf("君", "の", "名は"),
            listOf(
                SourceReadingSpan(0, 1, "君", "キミ", "키미"),
                SourceReadingSpan(1, 2, "の", "ノ", "노"),
                SourceReadingSpan(2, 4, "名は", "ナワ", "나와"),
            ), "키미노나와",
        )
        assertFalse(result?.approximate ?: true)
        assertEquals(listOf("키미", "노", "나와"), result?.words?.map { it.hangul })
    }
}
