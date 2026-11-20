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

    @Test fun explicitZeroSeekWithUnreadableClockMustNotReviveThePreviousLyric() {
        assertEquals(0L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 21_370L,
            timelineAdvancing = false, explicitSeekToZero = true,
            lastValidAgeMs = 500L,
        ))
        assertEquals(0L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = null,
            timelineAdvancing = false, explicitSeekToZero = true,
        ))
        // When playback resumes, a permanently unreadable player clock must
        // not paint the very first lyric as active indefinitely.
        assertNull(ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 0L,
            timelineAdvancing = true, explicitSeekToZero = true,
            lastValidAgeMs = 2_000L,
        ))
    }

    @Test fun acceptedSeekSampleRemainsValidAfterTheDiagnosticSeekWindowExpires() {
        // The Exo seek callback must replace the fallback sample, not rely on
        // the 2.5 s diagnostic marker that expires while playback is paused.
        assertEquals(0L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 0L, lastValidMs = 0L,
            timelineAdvancing = false, explicitSeekToZero = false,
            lastValidAgeMs = 9_000L,
        ))
        assertEquals(77_000L, ApplePausedPlaybackClockPolicy.select(
            currentMs = null, lastValidMs = 77_000L,
            timelineAdvancing = false, explicitSeekToZero = false,
            lastValidAgeMs = 9_000L,
        ))
    }

    @Test fun acceptedSeekClockSampleBelongsOnlyToTheCurrentPlayerAndSong() {
        val player = Any()
        val oldPlayer = Any()
        assertEquals(AppleAcceptedSeekClockSample(0L, "song-A"),
            ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
                callbackPlayer = player,
                positionSourcePlayer = player,
                positionMs = 0L,
                songId = "song-A",
            ))
        assertEquals(AppleAcceptedSeekClockSample(77_000L, "song-A"),
            ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
                callbackPlayer = player,
                positionSourcePlayer = player,
                positionMs = 77_000L,
                songId = "song-A",
            ))
        assertNull(ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
            callbackPlayer = oldPlayer,
            positionSourcePlayer = player,
            positionMs = 0L,
            songId = "song-A",
        ))
        assertNull(ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
            callbackPlayer = player,
            positionSourcePlayer = player,
            positionMs = 0L,
            songId = null,
        ))
        assertNull(ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
            callbackPlayer = player,
            positionSourcePlayer = null,
            positionMs = 0L,
            songId = "song-A",
        ))
        assertNull(ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
            callbackPlayer = player,
            positionSourcePlayer = player,
            positionMs = -1L,
            songId = "song-A",
        ))
        assertNull(ApplePausedPlaybackClockPolicy.acceptedSeekClockSample(
            callbackPlayer = player,
            positionSourcePlayer = player,
            positionMs = null,
            songId = "song-A",
        ))
    }

    @Test fun pausedSeekFallbackUsesNewestScopedEventNotAnExpiringMagicWindow() {
        assertEquals(ApplePausedClockFallback(0L, true),
            ApplePausedPlaybackClockPolicy.newestFallback(
                measuredMs = 21_370L, measuredAtMs = 1_000L,
                acceptedSeekMs = 0L, acceptedSeekAtMs = 2_000L,
            ))
        assertEquals(ApplePausedClockFallback(77_000L, true),
            ApplePausedPlaybackClockPolicy.newestFallback(
                measuredMs = 21_370L, measuredAtMs = 1_000L,
                acceptedSeekMs = 77_000L, acceptedSeekAtMs = 2_000L,
            ))
        // A real player sample after the seek supersedes the requested target.
        assertEquals(ApplePausedClockFallback(22_000L, false),
            ApplePausedPlaybackClockPolicy.newestFallback(
                measuredMs = 22_000L, measuredAtMs = 3_000L,
                acceptedSeekMs = 0L, acceptedSeekAtMs = 2_000L,
            ))
        // If the song/player identity does not match, the hook passes no seek.
        assertEquals(ApplePausedClockFallback(21_370L, false),
            ApplePausedPlaybackClockPolicy.newestFallback(
                measuredMs = 21_370L, measuredAtMs = 1_000L,
                acceptedSeekMs = null, acceptedSeekAtMs = null,
            ))
        assertEquals(ApplePausedClockFallback(null, false),
            ApplePausedPlaybackClockPolicy.newestFallback(
                measuredMs = null, measuredAtMs = null,
                acceptedSeekMs = null, acceptedSeekAtMs = null,
            ))
    }


    @Test fun pausedNonzeroSeekSurvivesTransientZeroFromThePlayerGetter() {
        val selected = ApplePausedPlaybackClockPolicy.newestFallback(
            measuredMs = 40_000L, measuredAtMs = 1_000L,
            acceptedSeekMs = 77_000L, acceptedSeekAtMs = 2_000L,
        )
        assertEquals(77_000L, ApplePausedPlaybackClockPolicy.select(
            currentMs = 0L, lastValidMs = selected.positionMs,
            timelineAdvancing = false,
            explicitSeekToZero = selected.fromSeek && selected.positionMs == 0L,
            lastValidAgeMs = 9_000L,
        ))
    }
}
