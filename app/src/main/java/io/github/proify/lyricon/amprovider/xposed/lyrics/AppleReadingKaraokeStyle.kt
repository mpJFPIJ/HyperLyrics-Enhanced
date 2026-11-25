package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Layout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import kotlin.math.abs
import kotlin.math.min

/** Pixel positions of a verified reading glyph within its one timed native unit. */
internal data class AppleMeasuredReadingGlyph(
    val glyphX: Float,
    val glyphWidthPx: Float,
    val unitStartPx: Float,
    val unitWidthPx: Float,
)

/** A single native timed word continues across verified Japanese wrap rows.
 * Calculate cumulative Hangul advance using the actual TextPaint rather than
 * splitting its clock according to Japanese characters or syllable counts. */
internal object AppleVerifiedReadingSweepPolicy {
    data class Bounds(val glyphStartPx: Float, val wordWidthPx: Float)
    class MeasuredWord internal constructor(
        private val starts: Map<Int, Float>,
        private val total: Float,
    ) {
        fun bounds(glyphOffset: Int): Bounds? = starts[glyphOffset]?.let {
            Bounds(it, total)
        }
    }

    fun measure(word: String, glyphAdvance: (Int) -> Float): MeasuredWord? {
        val starts = mutableMapOf<Int, Float>()
        var total = 0f
        for (index in word.indices) {
            if (!word[index].isLetterOrDigit()) continue
            val width = glyphAdvance(index)
            if (!width.isFinite() || width <= 0f) return null
            starts[index] = total
            total += width
        }
        return if (total.isFinite() && total > 0f) MeasuredWord(starts, total) else null
    }

    fun bounds(word: String, glyphOffset: Int, glyphAdvance: (Int) -> Float): Bounds? =
        measure(word, glyphAdvance)?.bounds(glyphOffset)
}

/** Measure after TextView/StaticLayout chose the actual font, kerning and wraps. */
internal object AppleReadingSweepLayout {
    fun measure(
        value: String,
        layout: Layout?,
        units: List<AppleTimedReadingUnit>,
    ): Map<Int, AppleMeasuredReadingGlyph> {
        if (layout == null || value.isEmpty()) return emptyMap()
        val unambiguous = AppleReadingGlyphCoveragePolicy.owners(units)
        val result = mutableMapOf<Int, AppleMeasuredReadingGlyph>()
        for (unit in units) {
            if (unit.isWholeLine) continue
            val offsets = (unit.start until unit.end).filter { offset ->
                offset in value.indices && value[offset].isLetterOrDigit() &&
                    unambiguous[offset] == unit
            }
            if (offsets.isEmpty()) continue
            val widths = offsets.map { offset ->
                val first = runCatching { layout.getPrimaryHorizontal(offset) }
                    .getOrDefault(Float.NaN)
                val end = runCatching { layout.getPrimaryHorizontal(offset + 1) }
                    .getOrDefault(Float.NaN)
                val advance = if (runCatching {
                        layout.getLineForOffset(offset) == layout.getLineForOffset(offset + 1)
                    }.getOrDefault(false)) abs(end - first) else 0f
                val fallback = layout.paint.measureText(value, offset, offset + 1)
                val width = advance.takeIf { it.isFinite() && it > 0f }
                    ?: fallback.takeIf { it.isFinite() && it > 0f } ?: 0f
                first to width
            }
            val total = widths.sumOf { it.second.toDouble() }.toFloat()
            if (!total.isFinite() || total <= 0f) continue
            // Measure each verified word once, not again for every glyph.
            // Each visual row has its own real TextPaint; no Japanese-source
            // character ratio or artificial per-row timestamp is introduced.
            val crossRowWord = unit.sweepFullReading?.let { word ->
                AppleVerifiedReadingSweepPolicy.measure(word) { glyph ->
                    layout.paint.measureText(word, glyph, glyph + 1)
                }
            }
            var wordOffset = 0f
            offsets.forEachIndexed { index, offset ->
                val (left, width) = widths[index]
                if (left.isFinite() && width > 0f) {
                    val originalOffset = unit.sweepStartOffset + offset - unit.start
                    val crossRow = crossRowWord?.bounds(originalOffset)
                    // A missing/invalid full-word measurement must not make
                    // two wrapped row fragments light simultaneously.
                    if (unit.sweepFullReading != null && crossRow == null)
                        return@forEachIndexed
                    result[offset] = AppleMeasuredReadingGlyph(
                        glyphX = left,
                        glyphWidthPx = width,
                        unitStartPx = crossRow?.glyphStartPx ?: wordOffset,
                        unitWidthPx = crossRow?.wordWidthPx ?: total,
                    )
                }
                wordOffset += width
            }
        }
        return result
    }
}

/**
 * The same paint owner for a regular full-width reading and a native soft-wrap
 * overlay. A partly sung glyph contains a horizontal shader: its *left pixels*
 * are already lit and its right pixels remain dim. An unsupported whole-line
 * clock retains the honest coarse tint instead of inventing syllable clocks.
 */
internal class AppleReadingKaraokeStyle : CharacterStyle(), UpdateAppearance {
    var progress = 0f
    var brightness = 1f
    var measured: AppleMeasuredReadingGlyph? = null
    var coarseLineClock = false

    override fun updateDrawState(paint: TextPaint) {
        val lit = brightness.coerceIn(0f, 1f)
        val dim = lit * 0.56f
        val fraction = progress.coerceIn(0f, 1f)
        paint.shader = null
        if (coarseLineClock || measured == null || fraction <= 0f || fraction >= 1f) {
            val intensity = dim + (lit - dim) * fraction
            val channel = (255f * intensity).toInt().coerceIn(0, 255)
            paint.color = Color.rgb(channel, channel, channel)
            return
        }
        val glyph = measured ?: return
        val boundary = glyph.glyphX + fraction * glyph.glyphWidthPx
        // Keep a narrow anti-aliased transition like the host's karaoke mask,
        // without brightening the entire glyph to one intermediate grey.
        val feather = min(2f, glyph.glyphWidthPx * 0.16f)
        if (feather <= 0f) return
        val brightChannel = (255f * lit).toInt().coerceIn(0, 255)
        val dimChannel = (255f * dim).toInt().coerceIn(0, 255)
        paint.color = Color.WHITE
        paint.shader = LinearGradient(
            boundary - feather / 2f, 0f,
            boundary + feather / 2f, 0f,
            Color.rgb(brightChannel, brightChannel, brightChannel),
            Color.rgb(dimChannel, dimChannel, dimChannel),
            Shader.TileMode.CLAMP,
        )
    }
}
