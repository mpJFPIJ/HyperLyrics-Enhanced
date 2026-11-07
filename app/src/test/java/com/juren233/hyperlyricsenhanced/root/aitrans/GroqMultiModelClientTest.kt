package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.*
import org.junit.Test

class GroqMultiModelClientTest {
    @org.junit.Test fun reasoningFormatIsModelSpecific() {
        org.junit.Assert.assertNull(
            GroqMultiModelClient.reasoningFormatFor(GroqMultiModelClient.FALLBACK_MODEL),
        )
        org.junit.Assert.assertEquals(
            "hidden",
            GroqMultiModelClient.reasoningFormatFor(GroqMultiModelClient.SECONDARY_FALLBACK_MODEL),
        )
    }

    @org.junit.Test fun groqErrorCategoryCannotLeakProviderMessage() {
        org.junit.Assert.assertEquals(
            "invalid_request_error",
            GroqMultiModelClient.safeErrorCategory(
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"private lyric\"}}",
            ),
        )
        org.junit.Assert.assertEquals(
            "unknown",
            GroqMultiModelClient.safeErrorCategory(
                "{\"error\":{\"type\":\"private lyric\"}}",
            ),
        )
        org.junit.Assert.assertEquals(
            "response_format",
            GroqMultiModelClient.safeErrorParameter(
                "{\"error\":{\"param\":\"response_format\",\"message\":\"private lyric\"}}",
            ),
        )
    }
    @Test fun researchRequiresConsentKeyAndSongIdentity() {
        val cfg = AiTranslationConfigs(webSearchEnabled = true, groqApiKey = "groq-secret")
        assertTrue(GroqMultiModelClient.canResearch(cfg, "Song", "Artist"))
        assertFalse(GroqMultiModelClient.canResearch(cfg.copy(webSearchEnabled = false), "Song", "Artist"))
        assertFalse(GroqMultiModelClient.canResearch(cfg.copy(groqApiKey = null), "Song", "Artist"))
        assertFalse(GroqMultiModelClient.canResearch(cfg, "Unknown Track", "Artist"))
    }
    @Test fun fallbackOnlyForRecoverableErrors() {
        assertTrue(GroqMultiModelClient.isFallbackError("http_503"))
        assertTrue(GroqMultiModelClient.isFallbackError("quality_invalid"))
        assertEquals("openai/gpt-oss-120b", GroqMultiModelClient.FALLBACK_MODEL)
        assertTrue(GroqMultiModelClient.isFallbackError("http_429"))
        assertFalse(GroqMultiModelClient.isFallbackError("http_401"))
        assertFalse(GroqMultiModelClient.isFallbackError("http_404"))
    }
    @Test fun logsNeverExposeGroqCredential() {
        val text = AiTranslationConfigs(groqApiKey = "test-secret").toString()
        assertFalse(text.contains("test-secret"))
        assertFalse(AiTranslationConfigs(groqApiKeys = listOf("secret-a", "secret-b"))
            .toString().contains("secret-a"))
    }
}
