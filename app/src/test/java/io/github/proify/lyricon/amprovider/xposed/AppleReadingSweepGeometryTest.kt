package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleReadingSweepGeometryTest {
    @Test fun `verified wrapped word keeps cumulative glyph width across visual rows`() {
        val widths = listOf(21f, 28f, 23f)
        assertEquals(AppleVerifiedReadingSweepPolicy.Bounds(0f, 72f),
            AppleVerifiedReadingSweepPolicy.bounds("노미소", 0) { widths[it] })
        assertEquals(AppleVerifiedReadingSweepPolicy.Bounds(21f, 72f),
            AppleVerifiedReadingSweepPolicy.bounds("노미소", 1) { widths[it] })
        assertEquals(AppleVerifiedReadingSweepPolicy.Bounds(49f, 72f),
            AppleVerifiedReadingSweepPolicy.bounds("노미소", 2) { widths[it] })
        var measuredGlyphs = 0
        val cachedWord = AppleVerifiedReadingSweepPolicy.measure("노미소") {
            measuredGlyphs++
            widths[it]
        }!!
        assertEquals(AppleVerifiedReadingSweepPolicy.Bounds(21f, 72f), cachedWord.bounds(1))
        assertEquals(AppleVerifiedReadingSweepPolicy.Bounds(49f, 72f), cachedWord.bounds(2))
        assertEquals(3, measuredGlyphs)
        // The second row only starts lighting once the first row has swept.
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(0.2f, 21f, 28f, 72f), 0f)
        assertEquals(1f, AppleReadingSweepGeometry.glyphCoverage(0.9f, 21f, 28f, 72f), 0f)
    }

    @Test fun `cross row width refuses an invalid glyph`() {
        assertEquals(null, AppleVerifiedReadingSweepPolicy.bounds("노미소", 3) { 20f })
        assertEquals(null, AppleVerifiedReadingSweepPolicy.bounds("노미소", 1) { index ->
            if (index == 1) Float.NaN else 20f
        })
    }

    @Test
    fun `halfway through a word fills half its measured pixel width not half its glyphs`() {
        // Three Korean glyphs have visibly different rendered widths:
        // 18px + 24px + 6px = 48px. At t=50%, 24px are lit, so the
        // second glyph must be 6/24 lit rather than uniformly tinted.
        assertEquals(1f, AppleReadingSweepGeometry.glyphCoverage(
            wordProgress = 0.5f, glyphStartPx = 0f, glyphWidthPx = 18f,
            wordWidthPx = 48f,
        ), 0.0001f)
        assertEquals(0.25f, AppleReadingSweepGeometry.glyphCoverage(
            wordProgress = 0.5f, glyphStartPx = 18f, glyphWidthPx = 24f,
            wordWidthPx = 48f,
        ), 0.0001f)
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(
            wordProgress = 0.5f, glyphStartPx = 42f, glyphWidthPx = 6f,
            wordWidthPx = 48f,
        ), 0.0001f)
    }

    @Test
    fun `sweep starts dark and ends fully bright without inventing extra pixels`() {
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(0f, 18f, 24f, 48f), 0f)
        assertEquals(1f, AppleReadingSweepGeometry.glyphCoverage(1f, 18f, 24f, 48f), 0f)
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(-0.3f, 18f, 24f, 48f), 0f)
        assertEquals(1f, AppleReadingSweepGeometry.glyphCoverage(1.3f, 18f, 24f, 48f), 0f)
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(0.8f, 18f, 0f, 48f), 0f)
    }

    @Test
    fun `invalid measured word width never paints unrelated reading glyphs`() {
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(0.8f, 0f, 12f, 0f), 0f)
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(
            0.8f, Float.NaN, 12f, 30f,
        ), 0f)
        assertEquals(0f, AppleReadingSweepGeometry.glyphCoverage(
            Float.NaN, 0f, 12f, 30f,
        ), 0f)
    }
}
