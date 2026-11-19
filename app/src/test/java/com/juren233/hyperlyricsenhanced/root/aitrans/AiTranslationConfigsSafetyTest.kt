package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslationConfigsSafetyTest {
    @Test
    fun `multi-key credentials never appear in config diagnostics`() {
        val configuration = AiTranslationConfigs(
            provider = "OPENAI",
            targetLanguage = "ko",
            apiKey = "secret-aaa",
            apiKeys = listOf("secret-aaa", "secret-bbb"),
            baseUrl = "https://example.org/v1",
            model = "test-model",
        )
        assertTrue(configuration.isUsable)
        val description = configuration.toString()
        assertFalse(description.contains("secret-aaa"))
        assertFalse(description.contains("secret-bbb"))
        assertTrue(description.contains("apiKeys=<redacted count=2>"))
    }

    @Test
    fun `legacy credentials remain usable while an empty pool is rejected`() {
        val legacy = AiTranslationConfigs(
            provider = "OPENAI", targetLanguage = "ko", model = "model",
            baseUrl = "https://example.org/v1", apiKey = "legacy-key",
        )
        val missing = legacy.copy(apiKey = null)
        assertTrue(legacy.isUsable)
        assertFalse(missing.isUsable)
        assertEquals(emptyList<String>(), missing.apiKeys)
    }
}
