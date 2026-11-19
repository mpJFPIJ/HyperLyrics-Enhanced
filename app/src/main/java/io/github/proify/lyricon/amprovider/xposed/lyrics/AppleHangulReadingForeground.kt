package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.TextView

internal data class AppleHangulReadingPaintOwners(
    val nativeVisible: Boolean,
    val outerOverlayVisible: Boolean,
)

/** The native flexbox measures the reading but must not paint its masked glyphs
 * on top of an identical unmasked foreground. Until the foreground is ready,
 * native drawing remains as a fail-safe. */
internal object AppleHangulReadingPaintPolicy {
    fun owners(
        rootAttached: Boolean,
        rootWidth: Int,
        rootHeight: Int,
        readingWidth: Int,
        readingHeight: Int,
        overlayAttached: Boolean,
    ): AppleHangulReadingPaintOwners {
        val ready = rootAttached && rootWidth > 0 && rootHeight > 0 &&
            readingWidth > 0 && readingHeight > 0 && overlayAttached
        return AppleHangulReadingPaintOwners(
            nativeVisible = !ready,
            outerOverlayVisible = ready,
        )
    }
}

/** Draw the *same* measured TextView Layout and live syllable spans above
 * Apple's FullWidthAlphaGradientFlexboxLayout. Unlike a second TextView, this
 * Drawable cannot reserve another line or drift to the next lyric row. */
internal class AppleHangulReadingForeground(
    private val holderRoot: ViewGroup,
    private val reading: TextView,
) : Drawable() {
    private val localRect = Rect()

    fun syncBounds() {
        val width = holderRoot.width
        val height = holderRoot.height
        if (width > 0 && height > 0 &&
            (bounds.width() != width || bounds.height() != height)
        ) setBounds(0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        if (!holderRoot.isAttachedToWindow || !reading.isShown ||
            reading.width <= 0 || reading.height <= 0
        ) return
        val layout = reading.layout ?: return
        localRect.set(0, 0, reading.width, reading.height)
        if (runCatching {
                holderRoot.offsetDescendantRectToMyCoords(reading, localRect)
            }.isFailure
        ) return
        val save = canvas.save()
        try {
            // A native flexbox can measure the baseline tightly even after
            // font padding is enabled. Its TextView rectangle must not crop
            // the upper/lower glyph overhang in our unmasked foreground.
            // The holder remains the hard boundary between adjacent lyrics.
            canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
            canvas.translate(
                (localRect.left + reading.compoundPaddingLeft - reading.scrollX).toFloat(),
                (localRect.top + reading.totalPaddingTop - reading.scrollY).toFloat(),
            )
            layout.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {
        // The host lyric row already applies the owning row's fade.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // The existing KaraokeSyllableStyle is the one color owner.
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
