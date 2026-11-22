package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleAcceptedOnlinePublicationTest {
    @Test fun preferenceReplayCannotBorrowGenerationOfAReplacementRequest() {
        val tracker = AppleAcceptedOnlinePublication()
        val previousSong = Any()
        tracker.remember(previousSong, 3)
        assertEquals(3, tracker.generationFor(previousSong, 3))
        assertNull(tracker.generationFor(previousSong, 4))
    }

    @Test fun replacingPublishedObjectRequiresNewProofEvenForSameSongValues() {
        val tracker = AppleAcceptedOnlinePublication()
        val first = String(charArrayOf('A'))
        val second = String(charArrayOf('A'))
        tracker.remember(first, 3)
        assertNull(tracker.generationFor(second, 3))
    }
}
