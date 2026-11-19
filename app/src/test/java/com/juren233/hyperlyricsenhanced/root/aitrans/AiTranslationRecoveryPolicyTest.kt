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

    @Test fun nvidiaLongSongUsesStreamingAndEnoughOutputTokens() {
        assertTrue(AiTranslationRecoveryPolicy.useNvidiaStreaming("https://integrate.api.nvidia.com/v1"))
        assertTrue(AiTranslationRecoveryPolicy.useNvidiaStreaming("https://integrate.api.nvidia.com/v1/chat/completions"))
        assertFalse(AiTranslationRecoveryPolicy.useNvidiaStreaming("https://example.com/integrate.api.nvidia.com/v1"))
        assertFalse(AiTranslationRecoveryPolicy.useNvidiaStreaming("https://api.groq.com/openai/v1"))
        assertEquals(4096, AiTranslationRecoveryPolicy.outputTokenBudget(0, 20))
        assertTrue(AiTranslationRecoveryPolicy.outputTokenBudget(0, 43) > 4096)
        assertTrue(AiTranslationRecoveryPolicy.outputTokenBudget(0, 95) > 8192)
        assertEquals(7000, AiTranslationRecoveryPolicy.outputTokenBudget(7000, 43))
        assertEquals(16_000, AiTranslationRecoveryPolicy.outputTokenBudget(0, 500))
    }

    @Test fun streamReadTimeoutAllowsProgressButHasOverallDeadline() {
        assertEquals(45_000, AiTranslationRecoveryPolicy.streamReadTimeoutMillis())
        assertEquals(180_000L, AiTranslationRecoveryPolicy.streamDeadlineMillis())
    }
}
