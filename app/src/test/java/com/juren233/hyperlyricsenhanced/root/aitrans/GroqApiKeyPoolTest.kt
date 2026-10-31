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


    @Test fun diagnosticsUseStableOneBasedSlotNumbersWithoutCredentials() {
        val cfg = AiTranslationConfigs(groqApiKeys = (1..10).map { "sensitive-secret-$it" })
        val firstSlots = (1..10).map {
            GroqApiKeyPool.orderedSlotsForRequest(cfg).first()
        }
        assertEquals((1..10).toSet(), firstSlots.map { it.index }.toSet())
        firstSlots.forEach { slot ->
            assertEquals(10, slot.total)
            assertEquals("sensitive-secret-${slot.index}", slot.credential)
            assertEquals("key_index=${slot.index} key_total=10", slot.logLabel)
            assertFalse(slot.logLabel.contains("sensitive-secret"))
            assertFalse(slot.toString().contains("sensitive-secret"))
        }
    }

    @Test fun diagnosticSlotsStayAlignedWithRotatedCredentials() {
        val cfg = AiTranslationConfigs(groqApiKeys = listOf("secret-A", "secret-B", "secret-C"))
        val slots = GroqApiKeyPool.orderedSlotsForRequest(cfg)
        assertEquals(3, slots.size)
        slots.forEach { slot ->
            assertEquals(listOf("secret-A", "secret-B", "secret-C")[slot.index - 1], slot.credential)
            assertTrue(slot.index in 1..3)
            assertEquals(3, slot.total)
        }
        assertTrue(GroqApiKeyPool.orderedSlotsForRequest(AiTranslationConfigs()).isEmpty())
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
