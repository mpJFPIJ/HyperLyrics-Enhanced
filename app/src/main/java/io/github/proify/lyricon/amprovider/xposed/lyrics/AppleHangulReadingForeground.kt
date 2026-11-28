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
import kotlin.math.roundToInt

internal data class AppleOverlayAddedInkLoss(val topPx: Int, val bottomPx: Int)

/** AOSP ViewGroup.offsetDescendantRectToMyCoords() already subtracts the
 * mapped TextView's own scrollX/Y and every intervening ancestor scroll.
 * Layout.draw() needs the original padding, not another scroll subtraction. */
internal object AppleOverlayMappedTextOrigin {
    fun left(mappedLeft: Int, paddingLeft: Int): Int = mappedLeft + paddingLeft
    fun top(mappedTop: Int, paddingTop: Int): Int = mappedTop + paddingTop
}

/** An alpha animated on Apple's Flexbox does not automatically affect a
 * drawable painted in the holder's ViewOverlay. These numeric measurements
 * describe both paths without assuming that the host animates either one.
 * "Pm" means per-mille; no text, view identity or absolute timestamp leaves
 * the patched process. */
internal data class AppleReadingOpacityPathEvidence(
    val sourceWords: Int,
    val nativeWordMinPm: Int,
    val nativeWordMaxPm: Int,
    val flexToHolderPm: Int,
    val overlayWordMinPm: Int,
    val overlayWordMaxPm: Int,
    val sharedAlphaLayers: Int,
)

internal object AppleReadingOpacityPathPolicy {
    fun summarize(
        wordToFlexAlphas: List<List<Float>>,
        flexToHolderAlphas: List<Float>,
    ): AppleReadingOpacityPathEvidence? {
        if (wordToFlexAlphas.isEmpty() ||
            wordToFlexAlphas.any { it.isEmpty() }
        ) return null
        if ((wordToFlexAlphas.flatten() + flexToHolderAlphas).any {
                !it.isFinite() || it < 0f || it > 1f
            }
        ) return null
        val nativeWordOpacity = wordToFlexAlphas.map { path ->
            path.fold(1f) { current, alpha -> current * alpha }
        }
        val sharedOpacity = flexToHolderAlphas.fold(1f) { current, alpha ->
            current * alpha
        }
        fun pm(value: Float) = (value * 1000f).roundToInt().coerceIn(0, 1000)
        return AppleReadingOpacityPathEvidence(
            sourceWords = nativeWordOpacity.size,
            nativeWordMinPm = pm(nativeWordOpacity.min()),
            nativeWordMaxPm = pm(nativeWordOpacity.max()),
            flexToHolderPm = pm(sharedOpacity),
            overlayWordMinPm = pm(nativeWordOpacity.min() * sharedOpacity),
            overlayWordMaxPm = pm(nativeWordOpacity.max() * sharedOpacity),
            sharedAlphaLayers = flexToHolderAlphas.count { it < 0.999f },
        )
    }
}

/** An overlay can be drawn by Android before our next scheduled binder pulse.
 * Its identity is the live holder generation, not the lyric string alone. */
internal class AppleOverlayPaintOwnership {
    private var ownerCheck: (() -> Boolean)? = null

    fun bind(check: () -> Boolean) {
        ownerCheck = check
    }

    fun isCurrent(): Boolean = runCatching {
        ownerCheck?.invoke() == true
    }.getOrDefault(false)
}

/** The holder's clip is not necessarily the first clip applied by Apple.
 * Report only ink lost *additionally* when our own hard clip is applied. */
internal object AppleOverlayClipPolicy {
    fun clippedInkLoss(
        inkTop: Int,
        inkBottom: Int,
        clipTop: Int,
        clipBottom: Int,
    ): AppleOverlayAddedInkLoss {
        val height = inkBottom - inkTop
        if (height <= 0) return AppleOverlayAddedInkLoss(0, 0)
        return AppleOverlayAddedInkLoss(
            topPx = (clipTop - inkTop).coerceIn(0, height),
            bottomPx = (inkBottom - clipBottom).coerceIn(0, height),
        )
    }

    fun addedInkLoss(
        inkTop: Int,
        inkBottom: Int,
        inheritedClipTop: Int,
        inheritedClipBottom: Int,
        appliedClipTop: Int,
        appliedClipBottom: Int,
    ): AppleOverlayAddedInkLoss {
        val inherited = clippedInkLoss(
            inkTop, inkBottom, inheritedClipTop, inheritedClipBottom,
        )
        val applied = clippedInkLoss(
            inkTop, inkBottom, appliedClipTop, appliedClipBottom,
        )
        return AppleOverlayAddedInkLoss(
            topPx = (applied.topPx - inherited.topPx).coerceAtLeast(0),
            bottomPx = (applied.bottomPx - inherited.bottomPx).coerceAtLeast(0),
        )
    }
}

internal data class AppleHangulReadingPaintOwners(
    val nativeVisible: Boolean,
    val outerOverlayVisible: Boolean,
)

/** The native flexbox measures the reading but must not paint its masked glyphs
 * on top of an identical unmasked foreground. Until the foreground is ready,
 * native drawing remains as a fail-safe. */
internal object AppleHangulReadingPaintPolicy {
    /** An active *line* is not proof that an untimed Hangul glyph is active.
     * Both ordinary ReadingView and the internal-soft-wrap painter use this
     * rule; only an owned timing unit (possibly coarse whole-line) can raise
     * a glyph above idle grey. */
    fun timedGlyphBrightness(activeVariant: Boolean, glyphHasTimingOwner: Boolean): Float =
        if (activeVariant && glyphHasTimingOwner) 1f else 0.52f

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
    private val ownership = AppleOverlayPaintOwnership()
    private val inheritedClip = Rect()
    private val appliedClip = Rect()
    private var sampledClip = false

    fun bindOwner(check: () -> Boolean) = ownership.bind(check)

    fun syncBounds() {
        val width = holderRoot.width
        val height = holderRoot.height
        if (width > 0 && height > 0 &&
            (bounds.width() != width || bounds.height() != height)
        ) setBounds(0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        if (!ownership.isCurrent() || !holderRoot.isAttachedToWindow || !reading.isShown ||
            reading.width <= 0 || reading.height <= 0
        ) return
        val layout = reading.layout ?: return
        localRect.set(0, 0, reading.width, reading.height)
        if (runCatching {
                holderRoot.offsetDescendantRectToMyCoords(reading, localRect)
            }.isFailure
        ) return
        val inheritedClipVisible = !sampledClip && canvas.getClipBounds(inheritedClip)
        val save = canvas.save()
        try {
            // A native flexbox can measure the baseline tightly even after
            // font padding is enabled. Its TextView rectangle must not crop
            // the upper/lower glyph overhang in our unmasked foreground.
            // The holder remains the hard boundary between adjacent lyrics.
            canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
            if (inheritedClipVisible && layout.lineCount > 0) {
                if (canvas.getClipBounds(appliedClip)) {
                    sampledClip = true
                    // Layout line bounds include fallback-font metrics and
                    // TextView font padding; base TextPaint metrics alone can
                    // under-report a Korean fallback glyph's vertical extent.
                    val layoutTop = AppleOverlayMappedTextOrigin.top(
                        localRect.top, reading.totalPaddingTop,
                    ) + layout.getLineTop(0)
                    val layoutBottom = AppleOverlayMappedTextOrigin.top(
                        localRect.top, reading.totalPaddingTop,
                    ) + layout.getLineBottom(layout.lineCount - 1)
                    val inheritedLoss = AppleOverlayClipPolicy.clippedInkLoss(
                        layoutTop, layoutBottom,
                        inheritedClip.top, inheritedClip.bottom,
                    )
                    val added = AppleOverlayClipPolicy.addedInkLoss(
                        layoutTop, layoutBottom,
                        inheritedClip.top, inheritedClip.bottom,
                        appliedClip.top, appliedClip.bottom,
                    )
                    var transformedAncestors = 0
                    var current: View? = reading
                    while (current != null && current !== holderRoot) {
                        if (abs(current.translationX) > 0.01f ||
                            abs(current.translationY) > 0.01f ||
                            abs(current.scaleX - 1f) > 0.01f ||
                            abs(current.scaleY - 1f) > 0.01f ||
                            abs(current.rotation) > 0.01f
                        ) transformedAncestors++
                        current = current.parent as? View
                    }
                    // Keep lyric content and canvas objects out of the log.
                    // A posted bounded sample avoids traversing the holder's
                    // view tree from the middle of Canvas.draw().
                    val summary = "extent=line_box hostH=${holderRoot.height} " +
                        "canvasBeforeTop=${inheritedClip.top} " +
                        "canvasBeforeBottom=${inheritedClip.bottom} " +
                        "canvasAfterTop=${appliedClip.top} " +
                        "canvasAfterBottom=${appliedClip.bottom} " +
                        "layoutTop=$layoutTop layoutBottom=$layoutBottom " +
                        "inheritedLostTop=${inheritedLoss.topPx} " +
                        "inheritedLostBottom=${inheritedLoss.bottomPx} " +
                        "manualLostTop=${added.topPx} " +
                        "manualLostBottom=${added.bottomPx} " +
                        "transformedAncestors=$transformedAncestors"
                    holderRoot.post {
                        AppleLyricUiRuntimeProbe.record(
                            "linewide_paint_clip", summary, holderRoot,
                        )
                    }
                }
            }
            canvas.translate(
                AppleOverlayMappedTextOrigin.left(
                    localRect.left, reading.compoundPaddingLeft,
                ).toFloat(),
                AppleOverlayMappedTextOrigin.top(
                    localRect.top, reading.totalPaddingTop,
                ).toFloat(),
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
