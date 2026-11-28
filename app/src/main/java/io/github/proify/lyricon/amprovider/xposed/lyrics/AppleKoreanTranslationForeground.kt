package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

/**
 * Repaints one active Korean meaning *outside* the nested Apple lyric
 * FullWidthAlphaGradientFlexboxLayout. TextView color/alpha changes alone
 * cannot bypass the parent mask and caused only the lower half to light up.
 *
 * A single lightweight Drawable follows the original TextView.layout:
 * no duplicate TextViews, no new layout pass, no new OCR/translation, and no
 * secondary 33-ms timer. Original source/phonetic rendering is unchanged.
 */
internal class AppleKoreanTranslationForeground(
    private val holderRoot: View,
    private val translatedViews: List<TextView>,
    initialForegroundColor: Int,
) : Drawable() {
    private var opacity: Int = 255
    private var foregroundColor: Int = initialForegroundColor
    private val inheritedClip = Rect()
    private val appliedClip = Rect()
    private var sampledClip = false
    private val ownership = AppleOverlayPaintOwnership()

    /** Native RecyclerView may paint before the next shared refresh tick. */
    fun bindOwner(check: () -> Boolean) = ownership.bind(check)

    /** Change only the color; never remove and recreate this visible painter. */
    fun setForegroundColor(color: Int) {
        if (foregroundColor == color) return
        foregroundColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!ownership.isCurrent()) return
        val parent = holderRoot as? ViewGroup ?: return
        // Measure all native meaning fragments in one draw. Sampling only
        // the first TextView misses a clipped last wrapped translation row.
        val sampleAll = !sampledClip && translatedViews.isNotEmpty() &&
            translatedViews.all { it.isAttachedToWindow && it.isShown &&
                it.width > 0 && it.height > 0 && (it.layout?.lineCount ?: 0) > 0
            } && canvas.getClipBounds(inheritedClip)
        var fragmentCount = 0
        var firstInkTop = Int.MAX_VALUE
        var lastInkBottom = Int.MIN_VALUE
        var maxTransformedAncestors = 0
        for (text in translatedViews) {
            if (!text.isAttachedToWindow || !text.isShown) continue
            val layout = text.layout ?: continue
            if (text.width <= 0 || text.height <= 0) continue
            val local = Rect(0, 0, text.width, text.height)
            if (!runCatching {
                    parent.offsetDescendantRectToMyCoords(text, local)
                }.isSuccess
            ) continue
            val save = canvas.save()
            try {
                // A tightly measured native TextView does not necessarily
                // include the ink overhang of its first/last glyph. Clip to
                // this lyric holder, as the Hangul-reading foreground does,
                // so no ascender/descender is cut at the TextView border.
                canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
                if (sampleAll && canvas.getClipBounds(appliedClip)) {
                    val metrics = text.paint.fontMetricsInt
                    val origin = AppleOverlayMappedTextOrigin.top(
                        local.top, text.totalPaddingTop,
                    )
                    firstInkTop = minOf(
                        firstInkTop, origin + layout.getLineBaseline(0) + metrics.top,
                    )
                    lastInkBottom = maxOf(
                        lastInkBottom,
                        origin + layout.getLineBaseline(layout.lineCount - 1) + metrics.bottom,
                    )
                    var transformed = 0
                    var ancestor: View? = text
                    while (ancestor != null && ancestor !== holderRoot) {
                        if (abs(ancestor.translationX) > 0.01f ||
                            abs(ancestor.translationY) > 0.01f ||
                            abs(ancestor.scaleX - 1f) > 0.01f ||
                            abs(ancestor.scaleY - 1f) > 0.01f ||
                            abs(ancestor.rotation) > 0.01f
                        ) transformed++
                        ancestor = ancestor.parent as? View
                    }
                    maxTransformedAncestors = maxOf(maxTransformedAncestors, transformed)
                    fragmentCount++
                }
                canvas.translate(
                    AppleOverlayMappedTextOrigin.left(
                        local.left, text.compoundPaddingLeft,
                    ).toFloat(),
                    AppleOverlayMappedTextOrigin.top(
                        local.top, text.totalPaddingTop,
                    ).toFloat(),
                )
                // Layout.draw uses TextView's TextPaint; restore its state
                // immediately so Apple's native next frame is unaffected.
                val paint = layout.paint
                val previousColor = paint.color
                val previousShader = paint.shader
                val previousAlpha = paint.alpha
                try {
                    paint.shader = null
                    paint.color = foregroundColor
                    paint.alpha = opacity
                    layout.draw(canvas)
                } finally {
                    paint.shader = previousShader
                    paint.color = previousColor
                    paint.alpha = previousAlpha
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
        if (sampleAll && fragmentCount == translatedViews.size) {
            sampledClip = true
            val added = AppleOverlayClipPolicy.addedInkLoss(
                firstInkTop, lastInkBottom,
                inheritedClip.top, inheritedClip.bottom,
                appliedClip.top, appliedClip.bottom,
            )
            val summary = "hostH=${holderRoot.height} " +
                "canvasBeforeTop=${inheritedClip.top} " +
                "canvasBeforeBottom=${inheritedClip.bottom} " +
                "canvasAfterTop=${appliedClip.top} " +
                "canvasAfterBottom=${appliedClip.bottom} " +
                "inkTop=$firstInkTop inkBottom=$lastInkBottom " +
                "manualLostTop=${added.topPx} " +
                "manualLostBottom=${added.bottomPx} " +
                "transformedAncestors=$maxTransformedAncestors"
            holderRoot.post {
                AppleLyricUiRuntimeProbe.record(
                    "meaning_paint_clip", summary, holderRoot,
                )
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Highlight foreground is deliberately one uniform user-visible
        // color; never import Apple's native gradient color filter.
    }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
