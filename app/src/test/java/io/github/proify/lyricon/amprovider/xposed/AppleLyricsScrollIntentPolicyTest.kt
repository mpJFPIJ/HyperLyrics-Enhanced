package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsScrollIntentPolicyTest {
    @Test
    fun `native auto follow movement does not become user browse`() {
        val state = AppleLyricsBlurRuntimeState()

        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 2))
        assertFalse(state.suspendedForScroll)
        AppleLyricsScrollIntentPolicy.endMotion(state)
        assertFalse(state.suspendedForScroll)

        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5, 6), scrollState = 0,
            suspendedForBrowse = state.suspendedForScroll, playing = true,
            nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
    }

    @Test
    fun `programmatic movement preserves an already pending recenter`() {
        val state = AppleLyricsBlurRuntimeState(
            suspendedForScroll = true,
            pendingProgrammaticRecenterPosition = 7,
        )

        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 0))
        assertEquals(7, state.pendingProgrammaticRecenterPosition)
        assertTrue(state.suspendedForScroll)
    }

    @Test
    fun `actual user drag cancels recenter and stays suspended through settling and idle`() {
        val state = AppleLyricsBlurRuntimeState(
            pendingProgrammaticRecenterPosition = 7,
        )

        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1))
        assertTrue(state.suspendedForScroll)
        assertNull(state.pendingProgrammaticRecenterPosition)
        assertFalse(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 2))
        AppleLyricsScrollIntentPolicy.endMotion(state)
        assertTrue(state.suspendedForScroll)

        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5, 6), scrollState = 0,
            suspendedForBrowse = state.suspendedForScroll, playing = true,
            nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
    }

    @Test
    fun `resizing while browsing leaves the listener in control`() {
        val state = AppleLyricsBlurRuntimeState(recyclerHeight = 900, settledAnchorTopY = 150f)
        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        AppleLyricsScrollIntentPolicy.endMotion(state)
        AppleLyricsScrollIntentPolicy.onViewportHeightChanged(state, 640)

        assertEquals(640, state.recyclerHeight)
        assertNull(state.settledAnchorTopY)
        assertTrue(state.suspendedForScroll)
    }

    @Test
    fun `unacknowledged automatic request cannot block recovery of the next active line`() {
        val state = AppleLyricsBlurRuntimeState()
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 2, nowMs = 12_000L)
        assertFalse(state.suspendedForScroll)
        assertEquals(2, state.pendingProgrammaticRecenterPosition)
        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(3), visible = setOf(9, 10), scrollState = 0,
            suspendedForBrowse = state.suspendedForScroll, playing = true,
            nowMs = 16_000L, lastRecoveryAtMs = state.lastFollowRecoveryAtMs,
        ))
        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        assertTrue(state.suspendedForScroll)
        assertNull(state.pendingProgrammaticRecenterPosition)
    }

    @Test
    fun `repeated movement clears blur only once until idle`() {
        val state = AppleLyricsBlurRuntimeState()

        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 2))
        assertFalse(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 2))
        assertFalse(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 0))
        AppleLyricsScrollIntentPolicy.endMotion(state)
        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 0))
    }
}
