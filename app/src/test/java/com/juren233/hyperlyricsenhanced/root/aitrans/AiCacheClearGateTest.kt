package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCacheClearGateTest {
    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `keyless pronunciation result is cancelled when song deletion overtakes its repair`() {
        val groups = AiCacheGroupEpochs()
        val generation = 7
        val scope = AiCacheScope("song", emptySet())
        val start = groups.current(scope)
        groups.advance(scope)
        requireCurrentAiResult(generation, start, generation, groups.current(scope))
    }

    @Test
    fun `an enqueue delayed across deletion keeps its original write fence`() {
        val epochs = AiCacheGroupEpochs()
        val scope = AiCacheScope("song", setOf("key"))
        val before = AiTranslationJobKey("key", AiCacheWriteTicket(scope, epochs.current(scope), 7))
        epochs.advance(scope)
        val afterSongDelete = AiTranslationJobKey("key", AiCacheWriteTicket(scope, epochs.current(scope), 7))
        assertFalse(before.ticket.isCurrent(7, epochs.current(scope)))
        assertTrue(afterSongDelete.ticket.isCurrent(7, epochs.current(scope)))
        assertFalse(before == afterSongDelete)
        val afterFullClear = afterSongDelete.copy(ticket = afterSongDelete.ticket.copy(generation = 8))
        assertFalse(afterSongDelete == afterFullClear)
        assertFalse(afterSongDelete.ticket.isCurrent(8, epochs.current(scope)))
    }

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
