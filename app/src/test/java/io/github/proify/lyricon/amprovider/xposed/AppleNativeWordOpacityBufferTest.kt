package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleNativeWordOpacityBufferTest {
    @Test fun `one detached Japanese word keeps its index and uses the observed fallback`() {
        val opacity = AppleNativeWordOpacityBuffer()
        opacity.reset(3)
        opacity.setKnown(0, 0.4f)
        opacity.markUnknown(1) // The host removed only the middle native word.
        opacity.setKnown(2, 0.75f)

        assertEquals(0.75f, opacity.maxOrDefault(), 0f)
        assertEquals(0.4f, opacity.atOrDefault(0), 0f)
        assertEquals(0.75f, opacity.atOrDefault(1), 0f)
        assertEquals(0.75f, opacity.atOrDefault(2), 0f)
        assertEquals(0.75f, opacity.atOrDefault(50), 0f)
    }

    @Test fun `reused holder clears stale alpha and observed maximum between frames`() {
        val opacity = AppleNativeWordOpacityBuffer()
        opacity.reset(3)
        opacity.setKnown(0, 0.9f)
        opacity.setKnown(1, 0.7f)
        opacity.setKnown(2, 0.4f)
        assertEquals(0.9f, opacity.maxOrDefault(), 0f)

        // The next binding has fewer words and a different per-word fade.
        opacity.reset(2)
        opacity.markUnknown(0)
        opacity.setKnown(1, 0.3f)
        assertEquals(0.3f, opacity.maxOrDefault(), 0f)
        assertEquals(0.3f, opacity.atOrDefault(0), 0f)
        assertEquals(0.3f, opacity.atOrDefault(1), 0f)
        assertEquals(0.3f, opacity.atOrDefault(2), 0f)

        opacity.reset(0)
        assertEquals(1f, opacity.maxOrDefault(), 0f)
        assertEquals(1f, opacity.atOrDefault(0), 0f)
    }

    @Test fun `all unmeasured words retain original neutral fallback`() {
        val opacity = AppleNativeWordOpacityBuffer()
        opacity.reset(2)
        opacity.markUnknown(0)
        opacity.markUnknown(1)
        assertEquals(1f, opacity.maxOrDefault(), 0f)
        assertEquals(1f, opacity.atOrDefault(-1), 0f)
    }
}
