package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CohereNativeChatPolicyTest {
    private val configs = AiTranslationConfigs(
        model = "north-small-translate-1.0",
        baseUrl = "https://api.cohere.ai/compatibility/v1",
    )

    @Test fun selectsDocumentedNativeChatV2OnlyForNorthSmall() {
        assertTrue(CohereNativeChatPolicy.applies(configs))
        assertTrue(CohereNativeChatPolicy.applies(configs.copy(baseUrl = "https://api.cohere.ai/v2")))
        assertEquals("https://api.cohere.ai/v2/chat", CohereNativeChatPolicy.endpoint())
        assertFalse(CohereNativeChatPolicy.applies(configs.copy(model = "command-a-plus")))
        assertFalse(CohereNativeChatPolicy.applies(configs.copy(baseUrl = "https://api.cohere.ai.evil.com/v1")))
        assertFalse(CohereNativeChatPolicy.applies(configs.copy(baseUrl = "https://api.other.com/v1")))
    }

    @Test fun requestFollowsNativeChatV2WithoutUnsupportedCompatibilityOptions() {
        val body = CohereNativeChatPolicy.requestBody(
            "north-small-translate-1.0",
            listOf(ChatMessage("system", "Return JSON"), ChatMessage("user", "Translate")),
            null,
        )
        assertTrue(body.contains("\"model\":\"north-small-translate-1.0\""))
        assertTrue(body.contains("\"messages\""))
        assertFalse(body.contains("response_format"))
        assertFalse(body.contains("temperature"))
        assertFalse(body.contains("max_tokens"))
    }

    @Test fun decodesNativeChatResponseTextBlocks() {
        val response = """{"message":{"role":"assistant","content":[{"type":"text","text":"translation-json"}]},"finish_reason":"COMPLETE"}"""
        val result = CohereNativeChatPolicy.parseResponse(response)
        assertEquals("translation-json", result.first)
        assertEquals("COMPLETE", result.second)
    }
}
