package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativePronunciationLanguagePolicyTest {
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
}
