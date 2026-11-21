package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCacheClearGateTest {
    @Test
    fun `any in flight clear prevents both stale sqlite reads and memory cache hits`() {
        val gate = AiCacheClearGate()
        assertTrue(gate.mayServeCache())

        gate.beginClear()
        assertFalse(gate.mayServeCache())
        gate.beginClear()
        gate.finishClear(success = true)
        assertFalse(gate.mayServeCache())
        gate.finishClear(success = true)
        assertTrue(gate.mayServeCache())
    }

    @Test
    fun `failed deletion never makes pre clear database contents available again`() {
        val gate = AiCacheClearGate()
        gate.beginClear()
        gate.finishClear(success = false)
        assertFalse(gate.mayReadDatabase())

        gate.beginClear()
        gate.finishClear(success = true)
        assertTrue(gate.mayReadDatabase())
    }
}
