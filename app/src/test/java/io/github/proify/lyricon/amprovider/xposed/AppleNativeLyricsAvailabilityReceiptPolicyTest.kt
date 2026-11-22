package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeLyricsAvailabilityReceiptPolicyTest {
    @Test fun unreadableNativePointerCannotBeMistakenForConfirmedEmptyLyrics() {
        assertFalse(canReportNativeLyricsPointerAvailability(null, null))
        assertFalse(canReportNativeLyricsPointerAvailability(null, "song-a"))
        assertFalse(canReportNativeLyricsPointerAvailability(Any(), null))
        assertFalse(canReportNativeLyricsPointerAvailability(Any(), ""))
    }

    @Test fun resolvedNativePointerMayReportActualLineAvailability() {
        assertTrue(canReportNativeLyricsPointerAvailability(Any(), "song-a"))
    }

    @Test fun aFailedNativeSectionReadIsUnknownNotAConfirmedEmptySong() {
        assertEquals(null, confirmedNativeSectionAvailability(null, emptyList()))
        assertEquals(null, confirmedNativeSectionAvailability(2, listOf(0, null)))
        assertEquals(null, confirmedNativeSectionAvailability(2, listOf(0)))
        assertEquals(null, confirmedNativeSectionAvailability(2, listOf(1)))
        assertEquals(null, confirmedNativeSectionAvailability(1, listOf(-1)))
        assertEquals(false, confirmedNativeSectionAvailability(0, emptyList()))
        assertEquals(false, confirmedNativeSectionAvailability(2, listOf(0, 0)))
        assertEquals(true, confirmedNativeSectionAvailability(2, listOf(null, 1)))
    }
}
