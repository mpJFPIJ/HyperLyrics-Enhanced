package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsBuiltReceiptPolicyTest {
    @Test fun presentationRejectsProvenOldQueueEvenWhenSongIdIsSame() {
        assertTrue(isClearlyStaleNativePresentation("A", "A", 777L, 999L))
        assertTrue(isClearlyStaleNativePresentation("A", "B", null, null))
        assertFalse(isClearlyStaleNativePresentation("A", "A", null, 999L))
        assertFalse(isClearlyStaleNativePresentation("A", null, 777L, 999L))
    }

    @Test fun validNativeColdStartMayEstablishCurrentVisit() {
        assertTrue(shouldBootstrapNativePlaybackVisit(
            currentSongId = null, builtSongId = "A", visibleSongId = "A",
            playbackSongId = "A", source = "apple", isModuleResult = false,
            resultQueueId = 123L, activeQueueId = 123L,
            scheduledVisitEpoch = 0L, activeVisitEpoch = 0L,
            nativeRequestProven = true,
        ))
    }

    @Test fun conflictingCurrentVisitOrUnprovenQueueCannotBootstrap() {
        assertFalse(shouldBootstrapNativePlaybackVisit(
            currentSongId = "B", builtSongId = "A", visibleSongId = "A",
            playbackSongId = "A", source = "apple", isModuleResult = false,
            resultQueueId = 123L, activeQueueId = 123L,
            scheduledVisitEpoch = 2L, activeVisitEpoch = 2L,
        ))
        assertFalse(shouldBootstrapNativePlaybackVisit(
            currentSongId = null, builtSongId = "A", visibleSongId = "A",
            playbackSongId = "A", source = "apple", isModuleResult = false,
            resultQueueId = null, activeQueueId = 123L,
            scheduledVisitEpoch = 0L, activeVisitEpoch = 0L,
        ))
    }

    @Test fun firstAResultMustNotBeSavedDuringSecondAVisit() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 12L, activeVisitEpoch = 14L,
            resultQueueId = 777L, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = false,
        ))
    }

    @Test fun oldNativeResultQueueCannotBorrowCurrentVisitAfterCallbackArrivesLate() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = 777L, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = false,
        ))
    }

    @Test fun nativeResultWithoutUsableQueueIdCannotMutateCache() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = 999L, isModuleResult = false,
            moduleRequestProven = false,
        ))
    }

    @Test fun moduleResultNeedsQueueProofWhenCurrentQueueIdentityIsKnown() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = false,
        ))
    }

    @Test fun matchingModuleQueueAndVisitCanBeSaved() {
        assertTrue(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = 999L, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = true,
        ))
    }

    @Test fun mismatchedSongNeverMutatesCacheEvenWhenVisitNumberMatches() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "B", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = 999L, activeQueueId = 999L, isModuleResult = false,
            moduleRequestProven = false,
        ))
    }

    @Test fun moduleWithRequestProofMayLackNativeQueueButCannotBorrowOldEpoch() {
        assertTrue(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = true,
        ))
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 12L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = 999L, isModuleResult = true,
            moduleRequestProven = true,
        ))
    }

    @Test fun appleNativeResultNeedsOriginalRequestProofEvenWithMatchingQueueIds() {
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = 999L, activeQueueId = 999L, isModuleResult = false,
            moduleRequestProven = false,
        ))
        assertTrue(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = 999L, activeQueueId = 999L, isModuleResult = false,
            moduleRequestProven = false, nativeRequestProven = true,
        ))
        assertTrue(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = 999L, isModuleResult = false,
            moduleRequestProven = false, nativeRequestProven = true,
        ))
        assertFalse(shouldAcceptAppleLyricsBuiltReceipt(
            builtSongId = "A", activeSongId = "A",
            scheduledVisitEpoch = 14L, activeVisitEpoch = 14L,
            resultQueueId = null, activeQueueId = null, isModuleResult = false,
            moduleRequestProven = false,
        ))
    }
}
