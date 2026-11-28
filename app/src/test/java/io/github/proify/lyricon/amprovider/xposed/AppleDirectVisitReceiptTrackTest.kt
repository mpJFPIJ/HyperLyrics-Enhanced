package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleDirectVisitReceiptTrackTest {
    @Test fun revisitOfSameSongRejectsEarlierVisitEvenWhenSongIdMatches() {
        val receipts = AppleDirectVisitReceiptTrack()
        val firstA = receipts.select("A")
        assertTrue(receipts.isCurrent(firstA, "A"))
        receipts.select("B")
        val secondA = receipts.select("A")
        assertFalse(receipts.isCurrent(firstA, "A"))
        assertTrue(receipts.isCurrent(secondA, "A"))
    }

    @Test fun repeatPublicationOfSameSongKeepsVisitIdentity() {
        val receipts = AppleDirectVisitReceiptTrack()
        val original = receipts.select("A")
        assertEquals(original, receipts.select("A"))
        assertFalse(receipts.isCurrent(original, "B"))
        assertFalse(receipts.isCurrent(0L, "A"))
    }

    @Test fun clearsMustCarryCurrentVisitAndCorrectSong() {
        val receipts = AppleDirectVisitReceiptTrack()
        val first = receipts.select("A")
        receipts.select("B")
        val current = receipts.select("A")
        assertFalse(receipts.isCurrentClear(first, "A"))
        assertFalse(receipts.isCurrentClear(current, "B"))
        assertTrue(receipts.isCurrentClear(current, "A"))
        assertTrue(receipts.isCurrentClear(current, null))
    }

    @Test fun stoppingPlaybackInvalidatesTheLastVisit() {
        val receipts = AppleDirectVisitReceiptTrack()
        val visit = receipts.select("A")
        receipts.select(null)
        assertFalse(receipts.isCurrent(visit, "A"))
    }

    @Test fun processRestartDoesNotReuseVisitOfSurvivingSystemUiPayload() {
        val previousProcess = AppleDirectVisitReceiptTrack(initialSequence = 91L)
        val previousVisit = previousProcess.select("A")
        val newProcess = AppleDirectVisitReceiptTrack(initialSequence = 107L)
        newProcess.select("B")
        newProcess.select("A")
        assertFalse(newProcess.isCurrent(previousVisit, "A"))
    }

    @Test fun aVisitSwitchCannotInterleaveAnAcceptedArrivalWithItsTicketIssuance() {
        val receipts = AppleDirectVisitReceiptTrack(initialSequence = 7L)
        val first = receipts.select("A")
        var issued = 0
        assertTrue(receipts.withCurrentVisit(first) { issued++ })
        receipts.select("B")
        assertFalse(receipts.withCurrentVisit(first) { issued++ })
        assertEquals(1, issued)
    }
}
