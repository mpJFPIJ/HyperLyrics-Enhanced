package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppleReadingPaintColorPolicyTest {
    @Test fun nativeZeroOpacityMustMakeWhiteKaraokeFullyTransparentNotOpaqueBlack() {
        val invisible = AppleReadingPaintColorPolicy.monochrome(channel = 255, opacity = 0f)
        assertEquals(0, invisible ushr 24)
        assertEquals(0x00FFFFFF, invisible)
        val half = AppleReadingPaintColorPolicy.monochrome(channel = 255, opacity = 0.5f)
        assertEquals(128, half ushr 24)
        assertEquals(255, half and 0xFF)
        val bright = AppleReadingPaintColorPolicy.monochrome(channel = 255, opacity = 1f)
        assertEquals(0xFFFFFFFF.toInt(), bright)
    }

    @Test fun brightnessAndSourceOpacityAreIndependentPaintDimensions() {
        val opaqueDim = AppleReadingPaintColorPolicy.monochrome(channel = 120, opacity = 1f)
        val transparentBright = AppleReadingPaintColorPolicy.monochrome(
            channel = 255, opacity = 120f / 255f,
        )
        assertNotEquals(opaqueDim, transparentBright)
        assertEquals(255, opaqueDim ushr 24)
        assertEquals(120, transparentBright ushr 24)
    }
}
