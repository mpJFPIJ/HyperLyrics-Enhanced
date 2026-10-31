package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.*
import org.junit.Test

class GroqMultiModelClientTest {
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
