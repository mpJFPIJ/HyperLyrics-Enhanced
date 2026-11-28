package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiKeyPoolTest {
    @Test
    fun `legacy single key works when multi key setting is missing`() {
        assertEquals(listOf("legacy-secret"), AiApiKeyPool.parse(null, "  legacy-secret  "))
        assertEquals(listOf("legacy-secret"), AiApiKeyPool.parse("", "legacy-secret"))
    }

    @Test
    fun `explicit key pool overrides legacy and removes duplicates and whitespace`() {
        assertEquals(
            listOf("key-one", "key-two", "key-three"),
            AiApiKeyPool.parse(
                configured = " key-one\n\nkey-two\nkey-one\r\n key-three ",
                legacy = "unused-legacy",
            ),
        )
    }

    @Test
    fun `pool rejects invalid entries and caps at twelve`() {
        val input = (1..20).joinToString("\n") { "key-$it" } +
            "\ninvalid with spaces\n" + "x".repeat(513)
        val keys = AiApiKeyPool.parse(input)
        assertEquals(12, keys.size)
        assertEquals("key-1", keys.first())
        assertEquals("key-12", keys.last())
        assertFalse(keys.any { it.contains(" ") || it.length > 512 })
    }

    @Test
    fun `round robin rotates the start across successive requests`() {
        val pool = listOf("first", "second", "third")
        val a = AiApiKeyPool.orderedForRequest(pool)
        val b = AiApiKeyPool.orderedForRequest(pool)
        assertEquals(pool.toSet(), a.toSet())
        assertEquals(pool.toSet(), b.toSet())
        assertTrue(a.first() != b.first())
        assertEquals(emptyList<String>(), AiApiKeyPool.orderedForRequest(emptyList()))
    }
}
