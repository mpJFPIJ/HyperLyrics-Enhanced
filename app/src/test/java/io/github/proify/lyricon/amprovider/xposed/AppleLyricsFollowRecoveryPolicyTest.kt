package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
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
            active = setOf(17),
            nowMs = 1_000L,
        )!!
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, setOf(17), nowMs = 1_500L,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, setOf(17), nowMs = 1_750L,
        ))
        // A settling/empty first sample must leave the same check available to
        // the next IDLE or layout event; there may be no new active-line update.
        assertTrue(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, adapter, setOf(17), nowMs = 2_100L,
        ))
    }

    @Test fun pendingFollowCheckCannotMoveAReusedRecyclerToAnOldSong() {
        val previousAdapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            previousAdapter, setOf(4), nowMs = 1_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, Any(), setOf(4), userBrowsing = false,
        ))
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, previousAdapter, setOf(5), userBrowsing = false,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.shouldEvaluate(
            pending, previousAdapter, setOf(5), nowMs = 3_000L,
        ))
    }

    @Test fun userDragCancelsTheDeferredFollowInsteadOfRecentering() {
        val adapter = Any()
        val pending = AppleLyricsFollowRecheckPolicy.begin(
            adapter, setOf(9), nowMs = 5_000L,
        )!!
        assertTrue(AppleLyricsFollowRecheckPolicy.isStale(
            pending, adapter, setOf(9), userBrowsing = true,
        ))
        assertFalse(AppleLyricsFollowRecheckPolicy.begin(
            adapter, emptySet(), nowMs = 5_000L,
        ) != null)
    }
}
