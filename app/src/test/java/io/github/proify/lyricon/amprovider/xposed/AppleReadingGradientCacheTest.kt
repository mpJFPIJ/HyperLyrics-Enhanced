package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AppleReadingGradientCacheTest {
    @Test fun `repeated draw of the same partially highlighted glyph reuses its shader`() {
        var created = 0
        val cache = AppleReadingGradientCache { _: Float, _: Float, _: Int, _: Int ->
            created++
            Any()
        }
        val original = cache.get(32f, 2f, 255, 74)
        assertSame(original, cache.get(32f, 2f, 255, 74))
        assertSame(original, cache.get(32f, 2f, 255, 74))
        assertEquals(1, created)
    }

    @Test fun `real karaoke progress brightness and font changes each require a new shader`() {
        var created = 0
        val cache = AppleReadingGradientCache { _: Float, _: Float, _: Int, _: Int ->
            created++
            Any()
        }
        val initial = cache.get(32f, 2f, 255, 74)
        val progressed = cache.get(33f, 2f, 255, 74)
        val resized = cache.get(33f, 1.5f, 255, 74)
        val faded = cache.get(33f, 1.5f, 144, 42)
        assertNotSame(initial, progressed)
        assertNotSame(progressed, resized)
        assertNotSame(resized, faded)
        assertSame(faded, cache.get(33f, 1.5f, 144, 42))
        assertEquals(4, created)
    }

    @Test fun `switching back to a previous shape never reuses a shader for the wrong boundary`() {
        var created = 0
        val cache = AppleReadingGradientCache { _: Float, _: Float, _: Int, _: Int ->
            created++
            Any()
        }
        val first = cache.get(32f, 2f, 255, 74)
        cache.get(33f, 2f, 255, 74)
        assertNotSame(first, cache.get(32f, 2f, 255, 74))
        assertEquals(3, created)
    }

    @Test fun `native fade alpha must invalidate even the same bright and dim glyph channels`() {
        var created = 0
        val cache = AppleReadingGradientCache { _: Float, _: Float, _: Int, _: Int ->
            created++
            Any()
        }
        val opaque = AppleReadingPaintColorPolicy.monochrome(255, 1f)
        val dim = AppleReadingPaintColorPolicy.monochrome(80, 1f)
        val transparent = AppleReadingPaintColorPolicy.monochrome(255, 0f)
        val transparentDim = AppleReadingPaintColorPolicy.monochrome(80, 0f)
        val first = cache.get(32f, 2f, opaque, dim)
        assertSame(first, cache.get(32f, 2f, opaque, dim))
        val fullyFaded = cache.get(32f, 2f, transparent, transparentDim)
        assertNotSame(first, fullyFaded)
        assertSame(fullyFaded, cache.get(32f, 2f, transparent, transparentDim))
        assertEquals(2, created)
    }
}
