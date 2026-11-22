package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleDirectSourceRequestOriginTest {
    @Test fun olderRequestKeepsItsOwnVisitEvenAfterNewAppleVisitArrives() {
        val origins = AppleDirectSourceRequestOrigin()
        origins.remember(7, "A", 42L)
        // Source's current visit may now be A2/43. Generation 7 still belongs to A1.
        assertEquals(42L, origins.visitFor(7, "A"))
        assertNull(origins.visitFor(7, "B"))
    }

    @Test fun newGenerationRevokesEarlierOneAndUnprovenOriginsFailClosed() {
        val origins = AppleDirectSourceRequestOrigin()
        origins.remember(7, "A", 42L)
        origins.remember(8, "A", 43L)
        assertNull(origins.visitFor(7, "A"))
        assertEquals(43L, origins.visitFor(8, "A"))
        origins.remember(9, "A", 0L)
        assertNull(origins.visitFor(9, "A"))
        org.junit.Assert.assertTrue(origins.needsProvenVisit("A"))
        origins.remember(10, "A", 50L)
        org.junit.Assert.assertFalse(origins.needsProvenVisit("A"))
    }

    @Test fun centralSongMustNotBorrowLastDirectVisitOfDifferentSong() {
        val lastDirect = AppleDirectSourceVisit("B", 8L)
        val origins = AppleDirectSourceRequestOrigin()
        origins.remember(1, "A", lastDirect.forSong("A"))
        assertNull(origins.visitFor(1, "A"))
        org.junit.Assert.assertTrue(origins.needsProvenVisit("A"))
        val actualA = AppleDirectSourceVisit("A", 9L)
        origins.remember(2, "A", actualA.forSong("A"))
        assertEquals(9L, origins.visitFor(2, "A"))
    }
}
