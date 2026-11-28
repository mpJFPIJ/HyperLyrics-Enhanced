package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeLyricsVisitRequestTrackTest {
    @Test fun reusedHostViewModelCannotAttributeOldPointerlessErrorToNewSong() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        track.record(viewModel, "B", 88L, visitEpoch = 2L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claimPointerless(viewModel, "B"))
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claimPointerless(viewModel, "A"))
    }

    @Test fun firstEverHostRequestCanStillReportARealPointerlessFailure() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        val origin = track.claimPointerless(viewModel, "A")
        assertTrue(origin is AppleNativeLyricsVisitRequestTrack.Origin.Verified)
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claimPointerless(viewModel, "A"))
    }

    @Test fun replayedA1TicketCannotBeBorrowedDuringA2() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        assertTrue(track.claim(viewModel, "A", 77L) is
            AppleNativeLyricsVisitRequestTrack.Origin.Verified)
        track.record(viewModel, "B", 88L, visitEpoch = 2L)
        track.record(viewModel, "A", 77L, visitEpoch = 3L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", 77L))
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", 77L))
    }

    @Test fun unknownQueueCannotMakeARepeatedSongLookLikeAUniqueRequest() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        track.claim(viewModel, "A", 77L)
        track.record(viewModel, "A", 99L, visitEpoch = 3L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", null))
        // A fully known *different* queue item may still be verified.
        assertTrue(track.claim(viewModel, "A", 99L) is
            AppleNativeLyricsVisitRequestTrack.Origin.Verified)
    }

    @Test fun saturatedLongLivedViewModelCanNeverPromoteEvictedOriginToVerified() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        for (index in 0..300) {
            track.record(viewModel, "song-$index", index.toLong() + 1L,
                visitEpoch = index.toLong() + 1L)
        }
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "song-300", 301L))
    }

    @Test fun lateFirstAIsAmbiguousAfterReturningToTheSameQueueItem() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        track.record(viewModel, "B", 88L, visitEpoch = 2L)
        track.record(viewModel, "A", 77L, visitEpoch = 3L)

        assertEquals(
            AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", 77L),
        )
        // An ambiguous result must not consume the old ticket and let the next
        // callback borrow the remaining, newer A2 ticket.
        assertEquals(
            AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", 77L),
        )
    }

    @Test fun firstAClaimedDuringBStillBlocksUnprovableSameQueueReplay() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        track.record(viewModel, "B", 88L, visitEpoch = 2L)
        val first = track.claim(viewModel, "A", 77L)
        assertTrue(first is AppleNativeLyricsVisitRequestTrack.Origin.Verified)
        assertEquals(1L, (first as AppleNativeLyricsVisitRequestTrack.Origin.Verified).visitEpoch)

        track.record(viewModel, "A", 77L, visitEpoch = 3L)
        val second = track.claim(viewModel, "A", 77L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous, second)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", 77L))
    }

    @Test fun matchingSongWithAnotherQueueItemCannotStealRequestOrigin() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Unknown,
            track.claim(viewModel, "A", 99L))
        val accepted = track.claim(viewModel, "A", 77L)
        assertTrue(accepted is AppleNativeLyricsVisitRequestTrack.Origin.Verified)
    }

    @Test fun repeatedSameVisitLoadIsOneOriginButTwoVisitsAreNot() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val viewModel = Any()
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        track.record(viewModel, "A", 77L, visitEpoch = 1L)
        assertTrue(track.claim(viewModel, "A", 77L) is
            AppleNativeLyricsVisitRequestTrack.Origin.Verified)
        // Both build-before and LiveData emission may inspect one load.
        assertTrue(track.claim(viewModel, "A", 77L) is
            AppleNativeLyricsVisitRequestTrack.Origin.Verified)
        track.record(viewModel, "A", 77L, visitEpoch = 3L)
        track.record(viewModel, "A", 77L, visitEpoch = 4L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Ambiguous,
            track.claim(viewModel, "A", null))
    }

    @Test fun differentViewModelCannotClaimAnotherViewModelsOrigin() {
        val track = AppleNativeLyricsVisitRequestTrack()
        val first = Any()
        track.record(first, "A", 77L, visitEpoch = 1L)
        assertEquals(AppleNativeLyricsVisitRequestTrack.Origin.Unknown,
            track.claim(Any(), "A", 77L))
        assertTrue(track.claim(first, "A", 77L) is
            AppleNativeLyricsVisitRequestTrack.Origin.Verified)
    }
}
