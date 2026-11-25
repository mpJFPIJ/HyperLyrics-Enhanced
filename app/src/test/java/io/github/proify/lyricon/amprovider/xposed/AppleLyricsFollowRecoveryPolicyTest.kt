package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsFollowRecoveryPolicyTest {
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

    @Test fun userDragCancelsTheDeferredFollowInsteadOfRecentering() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter, "song-a", setOf(9), nowMs = 5_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, "song-a", setOf(9), userBrowsing = true,
        ))
        val state = AppleLyricsBlurRuntimeState(pendingNativeFollowCheck = pending)
        AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState = 1)
        assertNull(state.pendingNativeFollowCheck)
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
}
