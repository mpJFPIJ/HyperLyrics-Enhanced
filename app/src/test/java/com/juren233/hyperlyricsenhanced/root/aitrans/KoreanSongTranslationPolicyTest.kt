package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanSongTranslationPolicyTest {
    @Test fun koreanTrackWithEnglishChorusIsNotTranslated() {
        assertTrue(KoreanSongTranslationPolicy.isKoreanSong(listOf(
            "그리워도", "I love you", "오늘도 난 너를", "너에게", "oh baby",
        )))
    }
    @Test fun japaneseTrackWithOccasionalKoreanLineIsStillEligible() {
        assertFalse(KoreanSongTranslationPolicy.isKoreanSong(listOf(
            "君の名は", "ありがとう", "별을 보며", "世界へ",
        )))
    }
    @Test fun pureEnglishIsNotMistakenForKorean() {
        assertFalse(KoreanSongTranslationPolicy.isKoreanSong(listOf("Hello", "my love")))
    }
    @Test fun koreanLinesAreNotRewrittenButJapaneseAndEnglishRemainEligible() {
        assertFalse(KoreanSongTranslationPolicy.shouldRequestTranslation("사랑해", "한국어"))
        assertFalse(KoreanSongTranslationPolicy.shouldRequestTranslation("I love you 사랑해", "ko"))
        assertTrue(KoreanSongTranslationPolicy.shouldRequestTranslation("君の名は", "한국어"))
        assertTrue(KoreanSongTranslationPolicy.shouldRequestTranslation("I love you", "한국어"))
        assertTrue(KoreanSongTranslationPolicy.shouldRequestTranslation("사랑해", "English"))
    }
}
