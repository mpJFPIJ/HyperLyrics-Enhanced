package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.View
import android.widget.TextView
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.ceil

/** Immutable native text geometry from before the soft-wrap painter reserves gaps.
 * Matching only lineCount is unsafe: a recycled word can keep two visual lines
 * but wrap at different UTF-16 offsets. */
internal data class AppleSoftWrapResumeGeometry(
    val text: String,
    val width: Int,
    val textSizePx: Float,
    val lineBounds: List<Pair<Int, Int>>,
    val lineSpacingExtra: Float,
    val lineSpacingMultiplier: Float,
)

internal object AppleSoftWrapResumePolicy {
    fun canResume(
        saved: AppleSoftWrapResumeGeometry,
        current: AppleSoftWrapResumeGeometry,
        sourceAttached: Boolean,
        holderAttached: Boolean,
        layoutPending: Boolean,
    ): Boolean = sourceAttached && holderAttached && !layoutPending &&
        saved.text == current.text && saved.width > 0 && saved.width == current.width &&
        saved.lineBounds.size > 1 && saved.lineBounds == current.lineBounds &&
        abs(saved.textSizePx - current.textSizePx) < 0.5f &&
        abs(saved.lineSpacingExtra - current.lineSpacingExtra) < 0.5f &&
        abs(saved.lineSpacingMultiplier - current.lineSpacingMultiplier) < 0.01f
}

/** Paint the readings of all but the last *measured* line of one native
 * internally wrapped TextView. Android still draws the original Japanese
 * TextView, including its own word shader and click target; extra line spacing
 * reserves a real non-overlapping slot immediately under each visual source
 * line. The last reading remains a regular full-width ReadingView after this
 * native source cell, before the translation.
 *
 * This drawable never rewrites source text, inserts synthetic source words or
 * divides a phonetic token according to its Japanese character count. */
internal class AppleNativeSoftWrapReadingOverlay(
    private val holderRoot: ViewGroup,
    private val source: TextView,
    private val readingTextPx: Float,
    private val pronunciation: String,
    private val rows: List<AppleInterleavedReadingRow>,
    private val timedVariants: List<List<AppleTimedReadingUnit>>,
) : Drawable() {
    private class ReadingStyle : CharacterStyle(), UpdateAppearance {
        var progress = 0f
        var brightness = 0.52f

        override fun updateDrawState(paint: TextPaint) {
            val intensity = (brightness * (0.56f + 0.44f * progress)).coerceIn(0f, 1f)
            val channel = (255f * intensity).toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = Color.rgb(channel, channel, channel)
        }
    }

    private data class PaintedRow(
        val visualLine: Int,
        val layout: StaticLayout,
        val styles: List<Pair<Int, ReadingStyle>>,
        val variantOwners: List<Map<Int, AppleReadingGlyphCoveragePolicy.GlyphOwner>>,
    )

    private val originalLineSpacingExtra = source.lineSpacingExtra
    private val originalLineSpacingMultiplier = source.lineSpacingMultiplier
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = readingTextPx
        typeface = source.typeface
        color = Color.rgb(110, 110, 110)
    }
    private val measuredWidth = (source.width - source.compoundPaddingLeft -
        source.compoundPaddingRight).coerceAtLeast(0)
    private fun currentGeometry(): AppleSoftWrapResumeGeometry? {
        val native = source.layout ?: return null
        return AppleSoftWrapResumeGeometry(
            text = source.text.toString(),
            width = source.width,
            textSizePx = source.textSize,
            lineBounds = (0 until native.lineCount).map { index ->
                native.getLineStart(index) to native.getLineEnd(index)
            },
            lineSpacingExtra = source.lineSpacingExtra,
            lineSpacingMultiplier = source.lineSpacingMultiplier,
        )
    }

    private val originalGeometry = requireNotNull(currentGeometry()) {
        "Native soft-wrap reading requires a measured source layout"
    }
    private val paintedRows: List<PaintedRow>
    private val reservedGapPx: Int
    private var installed = false

    init {
        require(rows.size > 1 && rows.indices.all { rows[it].insertAfterSourceCell == it })
        require(measuredWidth > 0 && readingTextPx > 0f)
        paintedRows = rows.dropLast(1).map { row ->
            val variants = AppleInterleavedReadingTimingPolicy.project(
                timedVariants, pronunciation, rows, row,
            )
            val text = SpannableString(row.text)
            val styles = AppleReadingGlyphCoveragePolicy.offsets(row.text, variants).map { offset ->
                val style = ReadingStyle()
                text.setSpan(style, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offset to style
            }
            PaintedRow(
                visualLine = row.insertAfterSourceCell,
                layout = StaticLayout.Builder.obtain(
                    text, 0, text.length, TextPaint(paint), measuredWidth,
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build(),
                styles = styles,
                variantOwners = variants.map { units ->
                    AppleReadingGlyphCoveragePolicy.glyphOwners(row.text, units)
                },
            )
        }
        reservedGapPx = paintedRows.maxOf { it.layout.height } +
            ceil(readingTextPx * 0.3f).toInt().coerceAtLeast(2)
    }

    fun install(): Boolean {
        if (installed) return true
        // Installation must be atomic. If the holder rejects its drawable,
        // keeping the extra line spacing would permanently displace Japanese
        // rows even though no Hangul is being painted in the reserved slots.
        return runCatching {
            setBounds(0, 0, holderRoot.width, holderRoot.height)
            source.setLineSpacing(originalLineSpacingExtra + reservedGapPx,
                originalLineSpacingMultiplier)
            // The nested native flexbox masks lower halves of added text.
            // Paint once on its outer holder without cloning native words.
            holderRoot.overlay.add(this)
            installed = true
        }.onFailure {
            runCatching { holderRoot.overlay.remove(this) }
            restoreOriginalSpacingIfOwned()
        }.isSuccess
    }

    /** A holder can leave and re-enter the viewport without another native
     * adapter bind. Restore its painter only after the SAME source and exact
     * soft-line offsets are measured again. A recycled holder must fail closed. */
    fun resumeIfCompatible(currentHolder: ViewGroup?): Boolean {
        if (currentHolder !== holderRoot) return false
        if (installed) return true
        val geometry = currentGeometry() ?: return false
        if (!AppleSoftWrapResumePolicy.canResume(
                saved = originalGeometry,
                current = geometry,
                sourceAttached = source.isAttachedToWindow,
                holderAttached = holderRoot.isAttachedToWindow &&
                    holderRoot.width > 0 && holderRoot.height > 0,
                layoutPending = source.isLayoutRequested || holderRoot.isLayoutRequested,
            )
        ) return false
        var parent: View? = source
        while (parent != null && parent !== holderRoot) parent = parent.parent as? View
        if (parent !== holderRoot) return false
        return install()
    }

    /** A recycled holder must never retain modified source line spacing. */
    fun clear() {
        if (!installed) return
        installed = false
        runCatching { holderRoot.overlay.remove(this) }
        restoreOriginalSpacingIfOwned()
    }

    private fun restoreOriginalSpacingIfOwned() {
        if (abs(source.lineSpacingExtra - (originalLineSpacingExtra + reservedGapPx)) < 0.5f &&
            abs(source.lineSpacingMultiplier - originalLineSpacingMultiplier) < 0.01f
        ) runCatching {
            source.setLineSpacing(originalLineSpacingExtra, originalLineSpacingMultiplier)
        }
    }

    /** The last inserted reading child may be below the viewport even while
     * an earlier, internally wrapped Japanese visual row is on screen. */
    fun isSourceVisibleOnScreen(): Boolean = installed && source.isAttachedToWindow &&
        source.isShown && source.getGlobalVisibleRect(Rect())

    fun refresh(positionMs: Long?) {
        if (!installed) return
        val variant = positionMs?.let {
            AppleTimedPronunciationHighlightPolicy.activeVariant(it, timedVariants)
        }
        // The holder overlay bypasses alpha on Apple's nested native word
        // cells. Restore that same source-to-holder opacity instead of
        // painting a bright Hangul row beneath a dimmed Japanese source row.
        var view: View? = source
        var nativeOpacity = 1f
        while (view != null && view !== holderRoot) {
            nativeOpacity *= view.alpha
            view = view.parent as? View
        }
        if (view !== holderRoot) nativeOpacity = 1f
        nativeOpacity = nativeOpacity.coerceIn(0f, 1f)
        var changed = false
        for (row in paintedRows) for ((offset, style) in row.styles) {
            val owner = variant?.let { row.variantOwners.getOrNull(it)?.get(offset) }
            val progress = positionMs?.let {
                owner?.let { glyph -> AppleReadingGlyphCoveragePolicy.glyphProgress(glyph, it) }
            } ?: 0f
            val brightness = (if (variant == null) 0.52f else 1f) * nativeOpacity
            if (abs(style.progress - progress) > 0.01f ||
                abs(style.brightness - brightness) > 0.01f
            ) {
                style.progress = progress
                style.brightness = brightness
                changed = true
            }
        }
        if (changed) invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!installed || source.isLayoutRequested || !source.isAttachedToWindow || !source.isShown ||
            !holderRoot.isAttachedToWindow || holderRoot.width <= 0 || holderRoot.height <= 0
        ) return
        val native = source.layout ?: return
        if (native.lineCount != rows.size) return
        val rect = Rect(0, 0, source.width, source.height)
        if (runCatching { holderRoot.offsetDescendantRectToMyCoords(source, rect) }.isFailure)
            return
        if (bounds.width() != holderRoot.width || bounds.height() != holderRoot.height)
            setBounds(0, 0, holderRoot.width, holderRoot.height)
        val clipped = canvas.save()
        canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
        for (row in paintedRows) {
            val sourceBaseline = native.getLineBaseline(row.visualLine) +
                rect.top + source.totalPaddingTop - source.scrollY
            val inkBottom = sourceBaseline + source.paint.fontMetrics.descent
            val x = rect.left + source.compoundPaddingLeft - source.scrollX +
                native.getLineLeft(row.visualLine)
            val y = inkBottom + readingTextPx * 0.12f
            val saved = canvas.save()
            canvas.translate(x, y)
            row.layout.draw(canvas)
            canvas.restoreToCount(saved)
        }
        canvas.restoreToCount(clipped)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
