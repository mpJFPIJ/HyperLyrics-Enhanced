package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderCapabilitiesTest {
    private val cohere = AiTranslationConfigs(
        provider = "OPENAI", targetLanguage = "한국어",
        model = "north-small-translate-1.0",
        baseUrl = "https://api.cohere.ai/compatibility/v1",
    )

    @Test fun cohereOnlyRequestsTranslationAndKeepsPronunciationIndependent() {
        val capabilities = AiProviderCapabilities.forConfig(cohere)
        assertFalse(capabilities.requestAiPronunciation)
        assertFalse(capabilities.allowGroqFallback)
        assertFalse(capabilities.useJsonResponseFormat)
    }

    @Test fun previousProvidersKeepTheirOriginalBehavior() {
        val original = cohere.copy(model = "gemini-3.8-flash", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai")
        val capabilities = AiProviderCapabilities.forConfig(original)
        assertTrue(capabilities.requestAiPronunciation)
        assertTrue(capabilities.allowGroqFallback)
    }

    @Test fun coherePromptDoesNotDemandUnreliableRomaField() {
        val prompt = AITranslationPrompt.build(cohere, null)
        assertFalse(prompt.contains("Additional pronunciation output"))
        assertTrue(prompt.contains("한국어"))
    }
}
