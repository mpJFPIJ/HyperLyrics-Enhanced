package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.*
import org.junit.Test

class GroqApiKeyPoolTest {
    @Test fun legacySingleKeyIsAccepted() {
        assertEquals(listOf("legacy-key"), GroqApiKeyPool.configured(
            AiTranslationConfigs(groqApiKey = "legacy-key")))
    }

    @Test fun poolNormalizesAndCapsAtTwelve() {
        val cfg = AiTranslationConfigs(groqApiKeys =
            (1..15).map { "groq-$it" } + "groq-1" + "invalid key")
        val keys = GroqApiKeyPool.configured(cfg)
        assertEquals(12, keys.size)
        assertEquals("groq-12", keys.last())
    }

    @Test fun requestsRoundRobinIndependently() {
        val cfg = AiTranslationConfigs(groqApiKeys = listOf("a","b","c"))
        val a = GroqApiKeyPool.orderedForRequest(cfg)
        val b = GroqApiKeyPool.orderedForRequest(cfg)
        assertEquals(a.toSet(), b.toSet())
        assertNotEquals(a.first(), b.first())
        assertTrue(GroqApiKeyPool.orderedForRequest(AiTranslationConfigs()).isEmpty())
    }

    @Test fun retriesAreBoundedAndDoNotEvade429() {
        assertTrue(GroqApiKeyPool.canRetryTransient(503, 0, 2))
        assertTrue(GroqApiKeyPool.canRetryTransient(502, 0, 3))
        assertFalse(GroqApiKeyPool.canRetryTransient(503, 1, 3))
        assertFalse(GroqApiKeyPool.canRetryTransient(503, 0, 1))
        assertFalse(GroqApiKeyPool.canRetryTransient(429, 0, 3))
        assertFalse(GroqApiKeyPool.canRetryTransient(401, 0, 3))
    }
}
