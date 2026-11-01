package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslationRecoveryPolicyTest {
    @Test fun nonGeminiTimeoutFallsBackWhenGroqIsConfigured() {
        assertTrue(AiTranslationRecoveryPolicy.shouldUseGroqFallback("network_error", true))
        assertTrue(AiTranslationRecoveryPolicy.shouldUseGroqFallback("http_503", true))
        assertFalse(AiTranslationRecoveryPolicy.shouldUseGroqFallback("http_401", true))
        assertFalse(AiTranslationRecoveryPolicy.shouldUseGroqFallback("network_error", false))
    }

    @Test fun shortSongTimeoutIsBoundedButLongSongsHaveMoreTime() {
        assertEquals(90_000, AiTranslationRecoveryPolicy.readTimeoutMillis(false, 22))
        assertEquals(150_000, AiTranslationRecoveryPolicy.readTimeoutMillis(false, 58))
        assertEquals(150_000, AiTranslationRecoveryPolicy.readTimeoutMillis(true, 22))
    }

    @Test fun missingPronunciationCanBeRepairedForAnyPrimaryModel() {
        assertTrue(AiTranslationRecoveryPolicy.shouldRepairAfterSuccess(true, true))
        assertFalse(AiTranslationRecoveryPolicy.shouldRepairAfterSuccess(false, true))
        assertFalse(AiTranslationRecoveryPolicy.shouldRepairAfterSuccess(true, false))
    }
}
