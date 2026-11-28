package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsScrollIntentPolicyTest {
    @Test fun overlapClockFocusKeepsItsOwnObserverEvenWhenVisualBlurIsDisabled() {
        val overlap = setOf(10, 11)
        // Blur OFF is purely a paint option: it must not determine whether a
        // native clock overlap gets a second observation without callbacks.
        for (blurEnabled in listOf(false, true)) {
            assertEquals(96L, AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = overlap, playbackAdvancing = true,
                userBrowsing = false, attached = true, visible = true,
                blurEnabled = blurEnabled,
            ))
            assertEquals(1_000L, AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = overlap, playbackAdvancing = false,
                userBrowsing = false, attached = true, visible = true,
                blurEnabled = blurEnabled,
            ))
            assertEquals(1_000L, AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = overlap, playbackAdvancing = true,
                userBrowsing = false, attached = true, visible = false,
                blurEnabled = blurEnabled,
            ))
            assertNull(AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = overlap, playbackAdvancing = true,
                userBrowsing = true, attached = true, visible = true,
                blurEnabled = blurEnabled,
            ))
            assertNull(AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = overlap, playbackAdvancing = true,
                userBrowsing = false, attached = false, visible = false,
                blurEnabled = blurEnabled,
            ))
            assertNull(AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
                nativeActive = setOf(11), playbackAdvancing = true,
                userBrowsing = false, attached = true, visible = true,
                blurEnabled = blurEnabled,
            ))
        }
        // The independent clock watcher must wake visual blur on a verified
        // focus transition, but never spend a render frame for Blur OFF.
        assertTrue(AppleLyricsOverlapFocusPollPolicy.needsVisualRefresh(
            blurEnabled = true, nativeActiveChanged = false, focusChanged = true,
        ))
        assertTrue(AppleLyricsOverlapFocusPollPolicy.needsVisualRefresh(
            blurEnabled = true, nativeActiveChanged = true, focusChanged = false,
        ))
        assertFalse(AppleLyricsOverlapFocusPollPolicy.needsVisualRefresh(
            blurEnabled = false, nativeActiveChanged = false, focusChanged = true,
        ))
        assertFalse(AppleLyricsOverlapFocusPollPolicy.needsVisualRefresh(
            blurEnabled = true, nativeActiveChanged = false, focusChanged = false,
        ))
    }

    @Test fun latePreviousAdapterCallbackCannotCancelTheNewAdaptersUserDrag() {
        val oldAdapter = Any()
        val currentAdapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActiveForCurrentAdapter(
            state, oldAdapter, oldAdapter, "song", 4L, setOf(9), 1_000L,
        ))
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActiveForCurrentAdapter(
            state, currentAdapter, currentAdapter, "song", 4L, setOf(10), 2_000L,
        ))
        assertTrue(AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1))
        assertTrue(state.suspendedForScroll)
        val pendingBefore = state.pendingNativeFollowCheck

        assertFalse(AppleLyricsScrollIntentPolicy.observeNativeActiveForCurrentAdapter(
            state, oldAdapter, currentAdapter, "song", 4L, setOf(11), 3_000L,
        ))
        assertTrue(state.adapterRef?.get() === currentAdapter)
        assertEquals(setOf(10), state.lastActivePositions)
        assertTrue(state.suspendedForScroll)
        assertEquals(pendingBefore, state.pendingNativeFollowCheck)

        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActiveForCurrentAdapter(
            state, currentAdapter, currentAdapter, "song", 4L, setOf(11), 3_100L,
        ))
        assertTrue(state.suspendedForScroll)
    }

    @Test fun sameNativeActiveSetMustRearmFollowWhenVerifiedClockFocusChanges() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        val bothActive = setOf(10, 11)
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song", 5L, bothActive, nowMs = 1_000L,
        ))
        assertTrue(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 5L, bothActive, focusedPosition = 10,
            nowMs = 1_100L,
        ))
        // First pending check settled because row 10 was visible. Later the
        // Apple word clock advances to row 11 while setOf(10,11) is unchanged.
        state.pendingNativeFollowCheck = null
        assertFalse(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song", 5L, bothActive, nowMs = 3_000L,
        ))
        assertTrue(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 5L, bothActive, focusedPosition = 11,
            nowMs = 3_000L,
        ))
        assertEquals(11, state.lastFocusedActivePosition)
        assertEquals(bothActive, state.pendingNativeFollowCheck?.active)
        assertEquals(3_750L, state.pendingNativeFollowCheck?.notBeforeMs)
        val currentOwner = state.pendingNativeFollowCheck
        assertFalse(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, Any(), "song", 5L, bothActive, focusedPosition = 10,
            nowMs = 3_050L,
        ))
        assertFalse(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 6L, bothActive, focusedPosition = 10,
            nowMs = 3_050L,
        ))
        assertFalse(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 5L, bothActive, focusedPosition = null,
            nowMs = 3_050L,
        ))
        assertTrue(state.pendingNativeFollowCheck === currentOwner)
        assertFalse(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 5L, bothActive, focusedPosition = 11,
            nowMs = 3_100L,
        ))
        assertTrue(state.pendingNativeFollowCheck === currentOwner)

        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        assertNull(state.pendingNativeFollowCheck)
        assertFalse(AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
            state, adapter, "song", 5L, bothActive, focusedPosition = 10,
            nowMs = 4_000L,
        ))
        assertNull(state.pendingNativeFollowCheck)
        assertTrue(state.suspendedForScroll)
    }

    @Test
    fun `unreadable native scroll state must not masquerade as idle`() {
        assertEquals(0, AppleLyricsScrollStatePolicy.fromNativeResult(0))
        assertEquals(1, AppleLyricsScrollStatePolicy.fromNativeResult(1L))
        assertEquals(2, AppleLyricsScrollStatePolicy.fromNativeResult(2.toShort()))
        for (value in listOf(null, "0", -1, 3, Int.MAX_VALUE, 4_294_967_296L, 1.5)) {
            val state = AppleLyricsScrollStatePolicy.fromNativeResult(value)
            assertEquals(AppleLyricsScrollStatePolicy.UNKNOWN, state)
            assertFalse(AppleLyricsScrollStatePolicy.mayRestore(state))
        }
        assertTrue(AppleLyricsScrollStatePolicy.mayRestore(0))
        // A native settle transition is not proof of user dragging, but a
        // reflection error cannot authorize an unsolicited restore or follow.
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(6), scrollState =
                AppleLyricsScrollStatePolicy.fromNativeResult(null),
            suspendedForBrowse = false, playing = true,
            nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
    }

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
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            Any(), "song-a", setOf(2), nowMs = 1_000L,
        )!!
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = pending)
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 2)
        AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
            state, owner = pending, target = 2, nowMs = 12_000L, dispatched = true,
        )
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
    fun `failed reflective follow must not start cooldown or strand a pending target`() {
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            Any(), "song-a", setOf(17), nowMs = 1_000L,
        )!!
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = pending)
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 17)
        assertEquals(17, state.pendingProgrammaticRecenterPosition)

        // The host layout manager did not expose a compatible method (or
        // reflection threw). No actual scroll request was dispatched.
        AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
            state, owner = pending, target = 17, nowMs = 12_000L, dispatched = false,
        )
        assertNull(state.pendingProgrammaticRecenterPosition)
        assertEquals(0L, state.lastFollowRecoveryAtMs)
        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(17), visible = setOf(12, 13),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_100L, lastRecoveryAtMs = state.lastFollowRecoveryAtMs,
        ))

        // A previous real dispatch still owns its cooldown; the failed
        // subsequent attempt must not overwrite or erase that timestamp.
        state.lastFollowRecoveryAtMs = 6_000L
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 18)
        AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
            state, owner = pending, target = 18, nowMs = 12_200L, dispatched = false,
        )
        assertEquals(6_000L, state.lastFollowRecoveryAtMs)
    }

    @Test
    fun `successfully invoked native follow retains its cooldown`() {
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            Any(), "song-a", setOf(17), nowMs = 1_000L,
        )!!
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = pending)
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 17)
        AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
            state, owner = pending, target = 17, nowMs = 12_000L, dispatched = true,
        )
        assertEquals(17, state.pendingProgrammaticRecenterPosition)
        assertEquals(12_000L, state.lastFollowRecoveryAtMs)
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(18), visible = setOf(12, 13),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_100L, lastRecoveryAtMs = state.lastFollowRecoveryAtMs,
        ))
    }

    @Test
    fun `late old scroll return cannot throttle the replacement lyric owner`() {
        val adapter = Any()
        val old = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(17), nowMs = 1_000L,
        )!!
        val newer = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(18), nowMs = 1_100L,
        )!!
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = old)
        AppleLyricsScrollIntentPolicy.requestRecovery(state, target = 17)

        // The host method can synchronously invoke a new active-line bind.
        // Its old return must not complete a different recovery generation.
        state.pendingNativeFollowCheck = newer
        state.pendingProgrammaticRecenterPosition = 18
        assertFalse(AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
            state, owner = old, target = 17, nowMs = 12_000L, dispatched = true,
        ))
        assertEquals(0L, state.lastFollowRecoveryAtMs)
        assertEquals(18, state.pendingProgrammaticRecenterPosition)
        assertTrue(state.pendingNativeFollowCheck === newer)
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
