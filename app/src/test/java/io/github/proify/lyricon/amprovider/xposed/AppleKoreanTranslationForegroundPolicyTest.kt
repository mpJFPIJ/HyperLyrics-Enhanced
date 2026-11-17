package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleKoreanTranslationForegroundPolicyTest {
    @Test fun onlyActiveMeaningMayOverlayNativeKaraokeGradient() {
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(
            9_999, 10_000, 13_000))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(
            10_001, 10_000, 13_000))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(
            13_001, 10_000, 13_000))
    }
}
