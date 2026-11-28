package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOverlayClipPolicyTest {
    @Test fun mappedDescendantRectAlreadyIncludesNativeTextViewScrollOffsets() {
        // AOSP ViewGroup.offsetDescendantRectToMyCoords already subtracts the
        // child's mScrollX/mScrollY while mapping it into the holder.
        val rawLeft = 25
        val rawTop = 40
        val scrollX = 3
        val scrollY = 6
        val mappedLeft = rawLeft - scrollX
        val mappedTop = rawTop - scrollY
        assertEquals(24, AppleOverlayMappedTextOrigin.left(mappedLeft, paddingLeft = 2))
        assertEquals(36, AppleOverlayMappedTextOrigin.top(mappedTop, paddingTop = 2))
        // Subtracting the child scroll a second time incorrectly moves all
        // three native Hangul/meaning foregrounds by an extra (-3,-6)px.
        assertTrue(AppleOverlayMappedTextOrigin.left(mappedLeft, 2) != mappedLeft + 2 - scrollX)
        assertTrue(AppleOverlayMappedTextOrigin.top(mappedTop, 2) != mappedTop + 2 - scrollY)
    }

    @Test fun `holder hard clip reports only additional lost reading ink`() {
        val lost = AppleOverlayClipPolicy.addedInkLoss(
            inkTop = -8, inkBottom = 173,
            inheritedClipTop = -20, inheritedClipBottom = 200,
            appliedClipTop = 0, appliedClipBottom = 160,
        )
        assertEquals(8, lost.topPx)
        assertEquals(13, lost.bottomPx)
    }

    @Test fun `already inherited clipping is not falsely attributed to manual clip`() {
        val inherited = AppleOverlayClipPolicy.addedInkLoss(
            inkTop = -8, inkBottom = 173,
            inheritedClipTop = 0, inheritedClipBottom = 160,
            appliedClipTop = 0, appliedClipBottom = 160,
        )
        assertEquals(0, inherited.topPx)
        assertEquals(0, inherited.bottomPx)

        val partial = AppleOverlayClipPolicy.addedInkLoss(
            inkTop = -8, inkBottom = 173,
            inheritedClipTop = -3, inheritedClipBottom = 168,
            appliedClipTop = 0, appliedClipBottom = 160,
        )
        assertEquals(3, partial.topPx)
        assertEquals(8, partial.bottomPx)
    }

    @Test fun `empty ink extent cannot report phantom clipping`() {
        val lost = AppleOverlayClipPolicy.addedInkLoss(
            inkTop = 100, inkBottom = 100,
            inheritedClipTop = -20, inheritedClipBottom = 200,
            appliedClipTop = 0, appliedClipBottom = 160,
        )
        assertEquals(0, lost.topPx)
        assertEquals(0, lost.bottomPx)
    }
}
