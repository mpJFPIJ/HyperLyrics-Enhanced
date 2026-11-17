package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView

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
    private val foregroundColor: Int,
) : Drawable() {
    private var opacity: Int = 255

    override fun draw(canvas: Canvas) {
        for (text in translatedViews) {
            if (!text.isAttachedToWindow || !text.isShown) continue
            val layout = text.layout ?: continue
            if (text.width <= 0 || text.height <= 0) continue
            val local = Rect(0, 0, text.width, text.height)
            if (!runCatching {
                    holderRoot.offsetDescendantRectToMyCoords(text, local)
                }.isSuccess
            ) continue
            val save = canvas.save()
            try {
                canvas.clipRect(local)
                canvas.translate(
                    (local.left + text.compoundPaddingLeft - text.scrollX).toFloat(),
                    (local.top + text.totalPaddingTop - text.scrollY).toFloat(),
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
