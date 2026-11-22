package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLyricsPointerOriginIsolationTest {
    @Test fun moduleAndHostPointerOriginsUseObjectIdentityNotEquals() {
        assertEquals(
            WeakIdentityMap::class.java,
            AppleLyricsSupplementHooks::class.java
                .getDeclaredField("moduleLyricsPointerReceipts").type,
        )
        assertEquals(
            WeakIdentityMap::class.java,
            AppleLyricsSupplementHooks::class.java
                .getDeclaredField("nativeLyricsPointerOrigins").type,
        )
    }

    @Test fun sameValuedNativePointerWrappersCannotBorrowAnotherVisitReceipt() {
        val origins = WeakIdentityMap<Any, AppleNativeLyricsVisitRequestTrack.Origin>()
        val oldWrapper: Any = String(charArrayOf('p', 't', 'r'))
        val freshWrapper: Any = String(charArrayOf('p', 't', 'r'))
        origins[oldWrapper] = AppleNativeLyricsVisitRequestTrack.Origin.Verified(
            songId = "A", queueId = 77L, visitEpoch = 1L,
        )
        assertEquals(oldWrapper, freshWrapper)
        assertNull(origins[freshWrapper])
        assertEquals(
            AppleNativeLyricsVisitRequestTrack.Origin.Verified("A", 77L, 1L),
            origins[oldWrapper],
        )
    }
}
