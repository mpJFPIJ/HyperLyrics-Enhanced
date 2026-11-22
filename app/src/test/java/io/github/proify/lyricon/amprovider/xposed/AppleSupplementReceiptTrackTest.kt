package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSupplementReceiptTrackTest {
    @Test fun newerDeliveryWinsEvenWhenOlderDecodeFinishesLast() {
        val receipts = AppleSupplementReceiptTrack()
        val slowFirst = receipts.issue()
        val quickSecond = receipts.issue()
        var applied = "none"
        assertTrue(receipts.commitIfCurrent(quickSecond, "song", "song") { applied = "new" })
        assertFalse(receipts.commitIfCurrent(slowFirst, "song", "song") { applied = "old" })
        assertEquals("new", applied)
    }

    @Test fun clearInvalidatesPreviouslyIssuedDecode() {
        val receipts = AppleSupplementReceiptTrack()
        val pending = receipts.issue()
        receipts.invalidate()
        assertFalse(receipts.commitIfCurrent(pending, "A", "A") {
            throw AssertionError("cleared data was restored")
        })
    }

    @Test fun requestForPreviousTrackCannotReplaceCurrentStore() {
        val receipts = AppleSupplementReceiptTrack()
        val pendingA = receipts.issue()
        assertFalse(receipts.commitIfCurrent(pendingA, "A", "B") {
            throw AssertionError("A overwrote B")
        })
    }

    @Test fun invalidIdentityNeverCommits() {
        val receipts = AppleSupplementReceiptTrack()
        val ticket = receipts.issue()
        assertFalse(receipts.commitIfCurrent(ticket, null, "A") {
            throw AssertionError("missing payload song")
        })
        assertFalse(receipts.commitIfCurrent(ticket, "A", null) {
            throw AssertionError("missing active song")
        })
    }

    @Test fun supplementTranslationCannotReviveLyricsAfterClear() {
        val receipts = AppleSupplementReceiptTrack()
        val pendingTranslationEpoch = receipts.clearEpoch()
        receipts.invalidate()
        assertFalse(receipts.commitIfNotCleared(pendingTranslationEpoch, "A", "A") {
            throw AssertionError("translation revived cleared lyrics")
        })
        val currentTranslationEpoch = receipts.clearEpoch()
        // An unrelated full-lyrics receipt should not invalidate newer translation metadata.
        receipts.issue()
        assertTrue(receipts.commitIfNotCleared(currentTranslationEpoch, "A", "A") {})
    }

    @Test fun locallyDecodedModulePayloadCannotSurviveABAReturn() {
        val receipts = AppleSupplementReceiptTrack()
        receipts.observeSong("A")
        val firstVisit = receipts.issue()
        receipts.observeSong("B")
        receipts.observeSong("A")
        assertFalse(receipts.commitIfCurrent(firstVisit, "A", "A") {
            throw AssertionError("A1 module payload reached A2")
        })
    }

    @Test fun visitProofMustStillHoldInsideReceiptCommit() {
        val receipts = AppleSupplementReceiptTrack()
        val pending = receipts.issue()
        assertFalse(receipts.commitIfCurrent(pending, "A", "A", guard = { false }) {
            throw AssertionError("visit proof was no longer current")
        })
    }

    @Test fun staleVisitCannotEvenIssueTicketThatWouldCancelNewerDecode() {
        val receipts = AppleSupplementReceiptTrack()
        val liveTicket = receipts.issueIf { true }
        assertFalse(receipts.issueIf { false } != null)
        assertTrue(receipts.commitIfCurrent(requireNotNull(liveTicket), "A", "A") {})
    }

    @Test fun staleClearDoesNotInvalidateCurrentResult() {
        val receipts = AppleSupplementReceiptTrack()
        val current = receipts.issue()
        assertFalse(receipts.clearIfCurrent(guard = { false }) {
            throw AssertionError("stale clear ran")
        })
        assertTrue(receipts.commitIfCurrent(current, "A", "A") {})
    }

    @Test fun clearingSupplementDoesNotEndCurrentNativeLyricsPlaybackVisit() {
        val receipts = AppleSupplementReceiptTrack()
        receipts.observeSong("A")
        val nativeVisit = receipts.visitEpoch()
        receipts.invalidate()
        assertEquals(nativeVisit, receipts.visitEpoch())
        receipts.observeSong("B")
        receipts.observeSong("A")
        assertFalse(nativeVisit == receipts.visitEpoch())
    }
}
