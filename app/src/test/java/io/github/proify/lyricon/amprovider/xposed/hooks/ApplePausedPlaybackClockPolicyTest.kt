package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplePausedPlaybackClockPolicyTest {
    @Test fun advancingLyricClockDoesNotFreezeOnAnOldPlayerSample() {
        assertEquals(21_620L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 21_370L,
            timelineAdvancing = true, explicitSeekToZero = false,
            lastValidAgeMs = 250L,
        ))
        assertNull(ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 21_370L,
            timelineAdvancing = true, explicitSeekToZero = false,
            lastValidAgeMs = 2_000L,
        ))
        assertEquals(21_370L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = false,
            lastValidAgeMs = 2_000L,
        ))
    }
    @Test fun releaseBuildPreservesLastPositionAfterPlayerTemporarilyStopsReporting() {
        assertEquals(21_370L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = false))
        assertEquals(21_370L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 0L, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = false))
    }
    @Test fun seeksAndNewSongsNeverInheritOldKaraokePosition() {
        assertEquals(0L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 0L, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = true))
        assertEquals(0L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 0L, lastValidMs = 21_370L,
            timelineAdvancing = true, explicitSeekToZero = false))
        assertEquals(1_000L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 1_000L, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = false))
        assertNull(ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = null,
            timelineAdvancing = false, explicitSeekToZero = false))
    }
}
