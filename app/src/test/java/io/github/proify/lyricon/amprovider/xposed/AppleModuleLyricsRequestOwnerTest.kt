package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleModuleLyricsRequestOwnerTest {
    @Test fun pointerReceiptRetainsOriginWithoutKeepingViewModelObject() {
        val receipts = AppleModuleLyricsRequestOwner()
        val old = Any()
        receipts.register(old, "A", 100L, visitEpoch = 1L)
        val immutable = receipts.receipt(old)
        receipts.register(Any(), "B", 200L, visitEpoch = 2L)
        assertFalse(receipts.isCurrentReceipt(immutable, "A", 2L, 100L))
        assertFalse(receipts.isCurrentReceipt(immutable, "B", 2L, 200L))
    }

    @Test fun viewModelsWithSameEqualsImplementationStillHaveSeparateReceipts() {
        val receipts = AppleModuleLyricsRequestOwner()
        class EqualViewModel {
            override fun equals(other: Any?): Boolean = other is EqualViewModel
            override fun hashCode(): Int = 1
        }
        val old = EqualViewModel()
        val current = EqualViewModel()
        receipts.register(old, "A", 100L, visitEpoch = 1L)
        receipts.register(current, "A", 100L, visitEpoch = 3L)
        assertFalse(receipts.isCurrent(old, "A", 3L, 100L))
        assertTrue(receipts.isCurrent(current, "A", 3L, 100L))
    }

    @Test fun olderAViewModelCannotBecomeCurrentWhenSecondARequestStarts() {
        val receipts = AppleModuleLyricsRequestOwner()
        val firstA = Any()
        receipts.register(firstA, "A", 777L, visitEpoch = 2L)
        receipts.register(Any(), "B", 888L, visitEpoch = 3L)
        val currentA = Any()
        receipts.register(currentA, "A", 999L, visitEpoch = 4L)

        assertTrue(receipts.owns(firstA))
        assertEquals("A", receipts.requestedSongId(firstA))
        assertFalse(receipts.isCurrent(firstA, "A", 4L, 999L))
        assertTrue(receipts.isCurrent(currentA, "A", 4L, 999L))
    }

    @Test fun newerRequestForSameQueueInvalidatesOlderResult() {
        val receipts = AppleModuleLyricsRequestOwner()
        val earlier = Any()
        val later = Any()
        receipts.register(earlier, "A", 100L, visitEpoch = 4L)
        receipts.register(later, "A", 100L, visitEpoch = 4L)
        assertFalse(receipts.isCurrent(earlier, "A", 4L, 100L))
        assertTrue(receipts.isCurrent(later, "A", 4L, 100L))
    }

    @Test fun currentModuleIdentityCanBeProvenWithoutNativeQueueId() {
        val receipts = AppleModuleLyricsRequestOwner()
        val request = Any()
        receipts.register(request, "A", 0L, visitEpoch = 4L)
        assertTrue(receipts.isCurrent(request, "A", 4L, null))
        assertFalse(receipts.isCurrent(request, "A", 5L, null))
        assertFalse(receipts.isCurrent(request, "B", 4L, null))
    }

    @Test fun mismatchedActiveQueueRejectsRequestEvenWithSameSongId() {
        val receipts = AppleModuleLyricsRequestOwner()
        val request = Any()
        receipts.register(request, "A", 777L, visitEpoch = 4L)
        assertFalse(receipts.isCurrent(request, "A", 4L, 999L))
    }
}
