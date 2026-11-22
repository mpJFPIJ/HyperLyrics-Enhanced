package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsViewTrackingTest {
    @Test
    fun `each host view is registered once without merging equal but distinct identities`() {
        val tracking = AppleLyricsViewTracking()
        val first: Any = String(charArrayOf('v', 'i', 'e', 'w'))
        val second: Any = String(charArrayOf('v', 'i', 'e', 'w'))

        assertTrue(tracking.markLoadingViewSuppressedIfNew(first))
        assertFalse(tracking.markLoadingViewSuppressedIfNew(first))
        assertTrue(tracking.markLoadingViewSuppressedIfNew(second))
        assertTrue(tracking.markTranslationButtonForcedIfNew(first))
        assertFalse(tracking.markTranslationButtonForcedIfNew(first))
    }

    @Test
    fun `registration stores weak identity keys rather than owning native views`() {
        assertEquals(
            WeakIdentityMap::class.java,
            AppleLyricsViewTracking::class.java
                .getDeclaredField("suppressedLoadingViews").type,
        )
        assertEquals(
            WeakIdentityMap::class.java,
            AppleLyricsViewTracking::class.java
                .getDeclaredField("forcedTranslationButtons").type,
        )
    }
}
