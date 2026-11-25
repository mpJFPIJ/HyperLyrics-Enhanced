package io.github.proify.lyricon.amprovider.xposed

/**
 * The native word clock advances across the *rendered width* of its matching
 * Hangul reading, not one equally timed slot per Korean UTF-16 character.
 * Geometry is computed at bind/layout time and animation only samples a clock.
 */
internal object AppleReadingSweepGeometry {
    fun glyphCoverage(
        wordProgress: Float,
        glyphStartPx: Float,
        glyphWidthPx: Float,
        wordWidthPx: Float,
    ): Float {
        if (!wordProgress.isFinite() || !glyphStartPx.isFinite() ||
            !glyphWidthPx.isFinite() || !wordWidthPx.isFinite() ||
            glyphStartPx < 0f || glyphWidthPx <= 0f || wordWidthPx <= 0f ||
            glyphStartPx >= wordWidthPx || glyphStartPx + glyphWidthPx > wordWidthPx + 0.5f
        ) return 0f
        val litWidth = wordProgress.coerceIn(0f, 1f) * wordWidthPx
        return ((litWidth - glyphStartPx) / glyphWidthPx).coerceIn(0f, 1f)
    }
}
