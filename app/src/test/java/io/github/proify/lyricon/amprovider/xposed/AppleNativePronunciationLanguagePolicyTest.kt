package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativePronunciationLanguagePolicyTest {
    @Test fun trimsJapaneseHangulTrackWithoutAcceptingKoreanTrack() {
        assertEquals("ja-Hang", AppleNativePronunciationLanguagePolicy.firstJapaneseHangul(
            listOf("ko-Hang", " ja-Hang ", "ja-Kore")))
        assertNull(AppleNativePronunciationLanguagePolicy.firstJapaneseHangul(
            listOf("ko-Hang", "zh-Hant")))
    }

    @Test fun regionQualifiedJapaneseHangulTracksKeepTheirRealScriptIdentity() {
        assertEquals("ja-Kore-JP", AppleNativePronunciationLanguagePolicy.firstJapaneseHangul(
            listOf("ja-Latn", "ja-Kore-JP"),
        ))
        assertEquals("ja-Hang-KR", AppleNativePronunciationLanguagePolicy.firstJapaneseHangul(
            listOf("ja-Hang-KR"),
        ))
        assertEquals("ja-Kore-JP", AppleNativePronunciationLanguagePolicy.select(
            languages = listOf("ja-Latn", "ja-Kore-JP"),
            hangulFirst = true, allowNative = true,
            hasLatinText = false, supplementLanguage = "und-Latn",
        ))
        assertEquals("ja-Hang-KR", ApplePronunciationPolicy.selectLanguage(
            systemMatch = null, appleLanguages = listOf("ja-Latn", "ja-Hang-KR"),
            onlineFallbackLanguage = "und-Latn", hangulFirst = true,
        ))
    }

    @Test fun invalidOrTrailingHangulLikeSubtagsCannotImpersonateJapaneseScript() {
        assertNull(AppleNativePronunciationLanguagePolicy.firstJapaneseHangul(
            listOf("ja-x-Kore", "ja-Latn-Hang", "ja--Hang", "en-Hang", "ko-Kore"),
        ))
        assertEquals("ja-Latn", ApplePronunciationPolicy.selectLanguage(
            systemMatch = null,
            appleLanguages = listOf("ja-x-Kore", "ja-Latn"),
            onlineFallbackLanguage = null, hangulFirst = true,
        ))
    }

    @Test fun nativeHangulIsNotFilteredOutByLatinOnlySelection() {
        val available = listOf("ja-Latn", "ja-Kore")
        assertEquals("ja-Kore", AppleNativePronunciationLanguagePolicy.select(
            available, hangulFirst = true, allowNative = true,
            hasLatinText = true, supplementLanguage = "und-Latn"))
        assertEquals("ja-Latn", AppleNativePronunciationLanguagePolicy.select(
            available, hangulFirst = false, allowNative = true,
            hasLatinText = true, supplementLanguage = "und-Latn"))
        assertEquals("und-Latn", AppleNativePronunciationLanguagePolicy.select(
            available, hangulFirst = true, allowNative = false,
            hasLatinText = true, supplementLanguage = "und-Latn"))
        assertNull(AppleNativePronunciationLanguagePolicy.select(
            listOf("zh-Hant"), true, true, false, null))
    }

    @Test fun advertisedLatinCandidateHasTheSamePriorityInBothAppleMatchingHooks() {
        val available = listOf("ja-Latn", "ja-Hang")
        // Apple can advertise a Latin track before it materializes text.
        // The native getter, not the song-level setter, decides whether the
        // track has real content or should use our Hangul supplement.
        assertEquals("ja-Latn", ApplePronunciationPolicy.selectLanguage(
            systemMatch = null, appleLanguages = available,
            onlineFallbackLanguage = "und-Latn", hangulFirst = false,
        ))
        assertEquals("ja-Latn", AppleNativePronunciationLanguagePolicy.select(
            languages = available, hangulFirst = false, allowNative = true,
            hasLatinText = false, supplementLanguage = "und-Latn",
        ))
        assertEquals("ja-Hang", AppleNativePronunciationLanguagePolicy.select(
            languages = available, hangulFirst = true, allowNative = true,
            hasLatinText = false, supplementLanguage = "und-Latn",
        ))
        assertEquals("und-Latn", AppleNativePronunciationLanguagePolicy.select(
            languages = available, hangulFirst = false, allowNative = false,
            hasLatinText = false, supplementLanguage = "und-Latn",
        ))
    }
}
