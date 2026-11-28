package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsFollowRecoveryPolicyTest {
    @Test fun overlapCannotConfirmAnOffscreenDispatchedTargetViaAnotherActiveRow() {
        assertTrue(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(17),
            trustedFocus = 17, dispatchedTarget = null,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(17),
            trustedFocus = 18, dispatchedTarget = null,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(17),
            trustedFocus = 18, dispatchedTarget = 18,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(18),
            trustedFocus = 18, dispatchedTarget = 18,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(18),
            trustedFocus = 18, dispatchedTarget = 19,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
            nativeActive = setOf(17, 18), visible = setOf(17, 18),
            trustedFocus = null, dispatchedTarget = null,
        ))
    }

    @Test fun overlappingNativeRowsMustUseVerifiedFocusWithoutLargestRowGuess() {
        assertEquals(17, AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = setOf(17, 18), nativeFocusedPosition = 17,
        ))
        assertEquals(18, AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = setOf(17, 18), nativeFocusedPosition = 18,
        ))
        // An Apple active-set overlap is not evidence that the highest row is
        // the one the clock currently owns. Unknown and foreign rows fail closed.
        assertNull(AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = setOf(17, 18), nativeFocusedPosition = null,
        ))
        assertNull(AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = setOf(17, 18), nativeFocusedPosition = 19,
        ))
        assertNull(AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = emptySet(), nativeFocusedPosition = 17,
        ))
        assertEquals(17, AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            nativeActive = setOf(17), nativeFocusedPosition = 17,
        ))
    }

    @Test fun onlyOffscreenTrustedFocusRequiresFollowDuringNativeOverlap() {
        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(17, 18), visible = setOf(17),
            focusTarget = 18, scrollState = 0, suspendedForBrowse = false,
            playing = true, nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(17, 18), visible = setOf(18),
            focusTarget = 17, scrollState = 0, suspendedForBrowse = false,
            playing = true, nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(17, 18), visible = setOf(17),
            focusTarget = 17, scrollState = 0, suspendedForBrowse = false,
            playing = true, nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(17, 18), visible = setOf(17),
            focusTarget = 19, scrollState = 0, suspendedForBrowse = false,
            playing = true, nowMs = 12_000L, lastRecoveryAtMs = 0L,
        ))
    }

    @Test fun dispatchedScrollMustWaitForVisibleRowBeforeClaimingRecovery() {
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            Any(), "song-a", setOf(17), nowMs = 1_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.mayDispatchFollow(pending))
        assertNull(AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(pending))
        AppleLyricsFollowRecheckPolicy.noteScrollDispatched(
            pending, targetPosition = 17, nowMs = 1_750L,
        )
        assertFalse(AppleLyricsFollowRecheckPolicy.mayDispatchFollow(pending))
        assertFalse(AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(pending))
        // A non-visible invocation may be diagnosed, but this same pending
        // instance must not force-scroll again after the four-second gate.
        assertFalse(AppleLyricsFollowRecheckPolicy.mayDispatchFollow(pending))
        assertEquals(750L, AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(pending))
        // Competing layout, idle and native callbacks cannot enqueue multiple
        // follow confirmations for the same successful host method call.
        assertNull(AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(pending))
        AppleLyricsFollowRecheckPolicy.onDispatchConfirmationDelivered(pending)
        assertTrue(AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(pending))
        assertFalse(AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(pending))
    }

    @Test fun newActiveRowGetsAnIndependentVisualConfirmation() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(17), nowMs = 1_000L,
        )!!
        AppleLyricsFollowRecheckPolicy.noteScrollDispatched(pending, 17, 1_750L)
        assertEquals(750L, AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(pending))
        AppleLyricsFollowRecheckPolicy.onDispatchConfirmationDelivered(pending)
        assertTrue(AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(pending))
        // A changed real native row gets a new owner; stale callbacks for row
        // 17 cannot prevent row 18's independent recovery and observation.
        val next = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(18), nowMs = 5_900L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", setOf(18), userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.mayDispatchFollow(next))
        AppleLyricsFollowRecheckPolicy.noteScrollDispatched(next, 18, 6_650L)
        assertFalse(AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(next))
        assertEquals(750L, AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(next))
    }

    @Test fun idleOffscreenActiveRowMayRecoverAfterNativeSettleWindow() {
        assertTrue(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5, 6),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        ))
    }

    @Test fun neverOverrideBrowseNativeScrollPauseOrRecentRecovery() {
        val common = AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5, 6),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        )
        assertTrue(common)
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(7),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5),
            scrollState = 1, suspendedForBrowse = false, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5),
            scrollState = 0, suspendedForBrowse = true, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5),
            scrollState = 0, suspendedForBrowse = false, playing = false,
            nowMs = 12_000, lastRecoveryAtMs = 0,
        ))
        assertFalse(AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = setOf(7), visible = setOf(4, 5),
            scrollState = 0, suspendedForBrowse = false, playing = true,
            nowMs = 12_000, lastRecoveryAtMs = 10_000,
        ))
    }

    @Test fun pendingFollowCheckWaitsForNativeSettleAndCanRunOnLaterLayout() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter = adapter,
            songId = "song-a",
            active = setOf(17),
            nowMs = 1_000L,
        )!!
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, "song-a", setOf(17), nowMs = 1_500L,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, "song-a", setOf(17), nowMs = 1_750L,
        ))
        // A settling/empty first sample must leave the same check available to
        // the next IDLE or layout event; there may be no new active-line update.
        assertTrue(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, "song-a", setOf(17), nowMs = 2_100L,
        ))
    }

    @Test fun transientSettleOrEmptyViewportRecheckIsBoundedAndNotDuplicated() {
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            Any(), "song-a", setOf(17), nowMs = 1_000L,
        )!!
        assertNull(AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, nowMs = 1_749L))
        assertEquals(100L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 1_750L))
        // A layout and an idle callback in the same frame must not enqueue twice.
        assertNull(AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 1_750L))

        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertEquals(200L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 1_850L))
        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertEquals(400L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 2_050L))
        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertEquals(500L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 2_450L))
        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertNull(AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 2_950L))
    }

    @Test fun oldPendingFollowCannotCreateLateRetryAndNewActiveRowGetsFreshBudget() {
        val adapter = Any()
        val old = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(17), nowMs = 1_000L,
        )!!
        assertNull(AppleLyricsFollowRecheckPolicy.queueTransientRecheck(old, nowMs = 4_251L))
        val next = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(18), nowMs = 4_300L,
        )!!
        assertEquals(100L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(next, 5_050L))
        assertNull(AppleLyricsFollowRecheckPolicy.queueTransientRecheck(next, 5_050L))
    }

    @Test fun pendingFollowCheckCannotMoveAReusedRecyclerToAnOldSong() {
        val previousAdapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            previousAdapter, "song-a", setOf(4), nowMs = 1_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, Any(), "song-a", setOf(4), userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, previousAdapter, "song-a", setOf(5), userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, previousAdapter, "song-b", setOf(4), userBrowsing = false,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, previousAdapter, "song-a", setOf(5), nowMs = 3_000L,
        ))
    }

    @Test fun unreadableActiveRowsCannotCancelALiveFollowOrAuthorizeItsEvaluation() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(17), nowMs = 1_000L,
        )!!
        // Null means the native active-list reflection did not produce an
        // iterable, NOT Apple's observed empty active set. Keep the exact
        // owner available for one of the existing bounded rechecks.
        assertFalse(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", null, userBrowsing = false,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, "song-a", null, nowMs = 1_750L,
        ))
        assertEquals(100L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(
            pending, nowMs = 1_750L,
        ))
        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertTrue(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, "song-a", setOf(17), nowMs = 1_850L,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", emptySet(), userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, Any(), "song-a", null, userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-b", null, userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", null, userBrowsing = true,
        ))
    }

    @Test fun userDragCancelsTheDeferredFollowInsteadOfRecentering() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(9), nowMs = 5_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", setOf(9), userBrowsing = true,
        ))
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = pending)
        assertEquals(100L, AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, 5_750L))
        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        assertNull(state.pendingNativeFollowCheck)
        // Even if the already-posted callback is delivered, the runtime has
        // no pending instance to authorize a recenter over the user's drag.
        AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
        assertFalse(state.pendingNativeFollowCheck === pending)
        assertNull(AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", emptySet(), nowMs = 5_000L,
        ))
        assertNull(AppleLyricsFollowRecheckPolicy.begin(
            adapter, null, setOf(9), nowMs = 5_000L,
        ))
    }

    @Test fun laterBlurFrameMustNotDiscardCurrentAdaptersPendingFollow() {
        val oldAdapter = Any()
        val currentAdapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, oldAdapter)
        val old = AppleLyricsFollowRecheckPolicy.begin(
            oldAdapter, "song-a", setOf(8), nowMs = 1_000L,
        )!!
        state.pendingNativeFollowCheck = old
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, currentAdapter)
        assertNull(state.pendingNativeFollowCheck)

        val current = AppleLyricsFollowRecheckPolicy.begin(
            currentAdapter, "song-b", setOf(10), nowMs = 3_000L,
        )!!
        state.pendingNativeFollowCheck = current
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, currentAdapter)
        assertTrue(state.pendingNativeFollowCheck === current)
    }

    @Test fun sameNativeAdapterReusedForNextSongResetsFollowAndBlurOwners() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a")
        val pendingA = requireNotNull(AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(8), nowMs = 1_000L,
        ))
        state.lastActivePositions = setOf(8)
        state.lastFocusedActivePosition = 8
        state.lastFollowRecoveryAtMs = 1_500L
        state.pendingNativeFollowCheck = pendingA
        state.pendingProgrammaticRecenterPosition = 8

        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a")
        assertTrue(state.pendingNativeFollowCheck === pendingA)
        assertEquals(1_500L, state.lastFollowRecoveryAtMs)
        // An unavailable song ID is not evidence that Apple changed songs.
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, null)
        assertTrue(state.pendingNativeFollowCheck === pendingA)

        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-b")
        assertNull(state.pendingNativeFollowCheck)
        assertNull(state.pendingProgrammaticRecenterPosition)
        assertEquals(emptySet<Int>(), state.lastActivePositions)
        assertNull(state.lastFocusedActivePosition)
        assertEquals(0L, state.lastFollowRecoveryAtMs)
        // Row 8 may still be native-active for the NEW song; it must be a
        // fresh transition, not silently considered the old active set.
        assertEquals("song-b", state.presentationSongId)
    }

    @Test fun sameSongNewSourceRevisionRevokesQueuedNativeFollowAndCooldown() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a", 4L)
        val old = requireNotNull(AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(8), nowMs = 1_000L, revision = 4L,
        ))
        state.pendingNativeFollowCheck = old
        state.lastActivePositions = setOf(8)
        state.lastFollowRecoveryAtMs = 1_200L

        // Reobserving an unchanged presentation is not a new follow owner.
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a", 4L)
        assertSame(old, state.pendingNativeFollowCheck)
        assertEquals(1_200L, state.lastFollowRecoveryAtMs)

        assertFalse(AppleLyricsFollowRecheckPolicy.isStale(
            old, adapter, "song-a", setOf(8), userBrowsing = false, revision = 4L,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            old, adapter, "song-a", setOf(8), userBrowsing = false, revision = 5L,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            old, adapter, "song-a", setOf(8), nowMs = 2_000L, revision = 5L,
        ))

        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a", 5L)
        assertEquals("song-a", state.presentationSongId)
        assertEquals(5L, state.presentationRevision)
        assertNull(state.pendingNativeFollowCheck)
        assertEquals(emptySet<Int>(), state.lastActivePositions)
        assertEquals(0L, state.lastFollowRecoveryAtMs)
        val fresh = requireNotNull(AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(8), nowMs = 2_000L, revision = 5L,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.isStale(
            fresh, adapter, "song-a", setOf(8), userBrowsing = false, revision = 5L,
        ))
    }

    @Test fun sourceRevisionRefreshCannotSilentlyEndExplicitUserBrowse() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a", 4L)
        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        assertTrue(state.suspendedForScroll)

        // New content invalidates the previous focus, but not the user's drag intent.
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-a", 5L)
        assertTrue(state.suspendedForScroll)
        assertEquals(5L, state.presentationRevision)

        // A different song is genuinely a new presentation and may follow again.
        AppleLyricsScrollIntentPolicy.adoptAdapter(state, adapter, "song-b", 5L)
        assertFalse(state.suspendedForScroll)
    }

    @Test fun blurFrameFirstMustNotConsumeSameRowFollowObservationBeforeActiveCallback() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 4L, setOf(8), nowMs = 1_000L,
        ))
        val oldPending = requireNotNull(state.pendingNativeFollowCheck)
        state.lastFollowRecoveryAtMs = 1_200L

        // The blur frame can observe the new source before Apple emits its
        // active-lines callback. It must register a new follow owner itself.
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 5L, setOf(8), nowMs = 2_000L,
        ))
        val renewedPending = requireNotNull(state.pendingNativeFollowCheck)
        assertTrue(renewedPending !== oldPending)
        assertEquals(5L, renewedPending.revision)
        assertEquals(0L, state.lastFollowRecoveryAtMs)
        assertFalse(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 5L, setOf(8), nowMs = 2_001L,
        ))
        assertSame(renewedPending, state.pendingNativeFollowCheck)

        // The same ordering also applies when a new song reuses the adapter
        // and identical integer row positions without incrementing revision.
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-b", 5L, setOf(8), nowMs = 3_000L,
        ))
        val nextSongPending = requireNotNull(state.pendingNativeFollowCheck)
        assertEquals("song-b", nextSongPending.songId)
        assertTrue(nextSongPending !== renewedPending)
    }

    @Test fun unknownNativeActiveReflectionCannotErasePendingFollowOrOutboundFocus() {
        val adapter = Any()
        val state = AppleLyricsBlurRuntimeState()
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 4L, setOf(8), nowMs = 1_000L,
        ))
        val pending = requireNotNull(state.pendingNativeFollowCheck)

        // A blur frame with a failed native reflection is not an empty set.
        assertFalse(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 4L, null, nowMs = 1_001L,
        ))
        assertEquals(setOf(8), state.lastActivePositions)
        assertSame(pending, state.pendingNativeFollowCheck)

        // A genuinely observed empty set, unlike null, retires this owner.
        assertTrue(AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, "song-a", 4L, emptySet(), nowMs = 1_002L,
        ))
        assertEquals(emptySet<Int>(), state.lastActivePositions)
        assertNull(state.pendingNativeFollowCheck)
    }
}
