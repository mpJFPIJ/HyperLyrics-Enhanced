package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsRefreshBurstGateTest {
    private val first = AppleLyricsRefreshKey(
        songId = "song-A",
        revision = 12L,
        syncDisplayFlags = false,
        presentationGeneration = 4L,
    )

    @Test fun repeatedRequestForSamePendingPresentationRebindsOnlyOnce() {
        val gate = AppleLyricsRefreshBurstGate()

        assertTrue(gate.begin(first))
        assertFalse(gate.begin(first))
        assertFalse(gate.begin(first.copy()))
    }

    @Test fun distinctRevisionGenerationAndVisibilityRequestsRemainIndependent() {
        val gate = AppleLyricsRefreshBurstGate()

        assertTrue(gate.begin(first))
        assertTrue(gate.begin(first.copy(revision = 13L)))
        assertTrue(gate.begin(first.copy(presentationGeneration = 5L)))
        assertTrue(gate.begin(first.copy(syncDisplayFlags = true)))
        assertTrue(gate.begin(first.copy(songId = "song-B")))
    }

    @Test fun completedRequestCanRefreshAgainAfterAnotherFrame() {
        val gate = AppleLyricsRefreshBurstGate()

        assertTrue(gate.begin(first))
        gate.end(first)
        assertTrue(gate.begin(first))
    }

    @Test fun staleCompletionCannotReleaseDifferentPendingRequest() {
        val gate = AppleLyricsRefreshBurstGate()
        val next = first.copy(presentationGeneration = 5L)

        assertTrue(gate.begin(next))
        gate.end(first)
        assertFalse(gate.begin(next))
    }
}
