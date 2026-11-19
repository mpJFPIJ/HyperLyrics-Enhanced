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
}
