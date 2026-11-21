package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun differentRevisionRequestsInSameFrameProduceOneLatestRefresh() {
        val queue = AppleLyricsFrameRefreshQueue()
        assertTrue(queue.offer(first.copy(revision = null)))
        assertFalse(queue.offer(first.copy(revision = 13L)))
        assertFalse(queue.offer(first.copy(syncDisplayFlags = true)))
        assertEquals(
            first.copy(revision = 13L, syncDisplayFlags = true),
            queue.consume(first),
        )
        assertNull(queue.consume(first))
        assertTrue(queue.offer(first))
    }

    @Test fun newPresentationGenerationSchedulesItsOwnRefresh() {
        val queue = AppleLyricsFrameRefreshQueue()
        val nextGeneration = first.copy(presentationGeneration = first.presentationGeneration + 1L)
        assertTrue(queue.offer(first))
        assertTrue(queue.offer(nextGeneration))
        assertEquals(nextGeneration, queue.consume(nextGeneration))
        assertEquals(first, queue.consume(first))
    }

    @Test fun `layout retries are bounded and retain newest coalesced revision`() {
        val queue = AppleLyricsFrameRefreshQueue()
        assertTrue(queue.offer(first.copy(revision = 10L)))
        assertFalse(queue.offer(first.copy(revision = 13L)))
        assertEquals(13L, queue.peek(first)?.revision)
        assertTrue(AppleLyricsRefreshRetryPolicy.shouldRetry(0))
        assertTrue(AppleLyricsRefreshRetryPolicy.shouldRetry(7))
        assertFalse(AppleLyricsRefreshRetryPolicy.shouldRetry(8))
        assertEquals(13L, queue.consume(first)?.revision)
        assertTrue(queue.offer(first))
    }
}
