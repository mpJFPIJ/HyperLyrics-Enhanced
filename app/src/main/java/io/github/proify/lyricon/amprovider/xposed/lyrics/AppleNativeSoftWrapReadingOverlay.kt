package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
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
import android.view.View
import android.widget.TextView
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

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
    val typefaceIdentity: Any? = null,
    val contentWidth: Int = width,
    val compoundPaddingLeft: Int = 0,
    val compoundPaddingRight: Int = 0,
    val letterSpacing: Float = 0f,
    val textScaleX: Float = 1f,
)

internal enum class AppleSoftWrapResumeDecision { RESUME, WAIT, REBIND }

internal object AppleSoftWrapResumePolicy {
    /** Resolve non-measured states before constructing an expensive current
     * source snapshot. A stale owner wins over even a pending layout. */
    fun preflight(
        readingAttached: Boolean,
        sourceAttached: Boolean,
        holderAttached: Boolean,
        layoutPending: Boolean,
        ownerCurrent: Boolean,
    ): AppleSoftWrapResumeDecision? = when {
        !ownerCurrent -> AppleSoftWrapResumeDecision.REBIND
        !readingAttached || !sourceAttached || !holderAttached || layoutPending ->
            AppleSoftWrapResumeDecision.WAIT
        else -> null
    }

    /** The active karaoke pulse may run every 16 ms. A settled, installed
     * overlay can compare its live source without allocating a String and
     * List<Pair> geometry snapshot on every frame. Detach, pending layout or
     * revoked holder ownership must use the full WAIT/REBIND state machine. */
    inline fun fastInstalled(
        installed: Boolean,
        readingAttached: Boolean,
        sourceAttached: Boolean,
        holderAttached: Boolean,
        layoutPending: Boolean,
        ownerCurrent: Boolean,
        unchangedSource: () -> Boolean,
    ): Boolean = installed && readingAttached && sourceAttached && holderAttached &&
        !layoutPending && ownerCurrent && unchangedSource()

    /** A ViewOverlay can be drawn synchronously by the host after TextView
     * content changes, before our next scheduled ownership/layout pulse.
     * Check the live UTF-16 content and line offsets with no per-frame String,
     * Pair or List allocations before drawing old Hangul beneath new text. */
    inline fun sameSourceAndLines(
        savedText: String,
        currentText: CharSequence,
        savedLineBounds: List<Pair<Int, Int>>,
        currentLineCount: Int,
        getLineStart: (Int) -> Int,
        getLineEnd: (Int) -> Int,
    ): Boolean {
        if (savedText.length != currentText.length ||
            savedText.indices.any { savedText[it] != currentText[it] } ||
            savedLineBounds.size != currentLineCount
        ) return false
        for (index in savedLineBounds.indices) {
            if (savedLineBounds[index].first != getLineStart(index) ||
                savedLineBounds[index].second != getLineEnd(index)
            ) return false
        }
        return true
    }

    /** WAIT is a transient detach/layout; REBIND is a settled stale owner or geometry. */
    fun decision(
        saved: AppleSoftWrapResumeGeometry,
        current: AppleSoftWrapResumeGeometry?,
        readingAttached: Boolean,
        sourceAttached: Boolean,
        holderAttached: Boolean,
        layoutPending: Boolean,
        ownerCurrent: Boolean,
    ): AppleSoftWrapResumeDecision {
        preflight(readingAttached, sourceAttached, holderAttached, layoutPending, ownerCurrent)
            ?.let { return it }
        if (current == null) return AppleSoftWrapResumeDecision.WAIT
        return if (saved.text == current.text && saved.width > 0 &&
            saved.width == current.width && saved.contentWidth > 0 &&
            saved.contentWidth == current.contentWidth &&
            saved.compoundPaddingLeft == current.compoundPaddingLeft &&
            saved.compoundPaddingRight == current.compoundPaddingRight &&
            saved.typefaceIdentity == current.typefaceIdentity &&
            saved.lineBounds.size > 1 && saved.lineBounds == current.lineBounds &&
            abs(saved.textSizePx - current.textSizePx) < 0.5f &&
            abs(saved.lineSpacingExtra - current.lineSpacingExtra) < 0.5f &&
            abs(saved.lineSpacingMultiplier - current.lineSpacingMultiplier) < 0.01f &&
            abs(saved.letterSpacing - current.letterSpacing) < 0.001f &&
            abs(saved.textScaleX - current.textScaleX) < 0.001f
        ) AppleSoftWrapResumeDecision.RESUME else AppleSoftWrapResumeDecision.REBIND
    }

    fun canResume(
        saved: AppleSoftWrapResumeGeometry,
        current: AppleSoftWrapResumeGeometry,
        sourceAttached: Boolean,
        holderAttached: Boolean,
        layoutPending: Boolean,
    ): Boolean = decision(
        saved, current, readingAttached = true, sourceAttached = sourceAttached,
        holderAttached = holderAttached, layoutPending = layoutPending,
        ownerCurrent = true,
    ) == AppleSoftWrapResumeDecision.RESUME
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
    private data class PaintedRow(
        val visualLine: Int,
        val layout: StaticLayout,
        val styles: List<Pair<Int, AppleReadingKaraokeStyle>>,
        val variantOwners: List<Map<Int, AppleReadingGlyphCoveragePolicy.GlyphOwner>>,
        val variantGeometry: List<Map<Int, AppleMeasuredReadingGlyph>>,
    )

    private val originalLineSpacingExtra = source.lineSpacingExtra
    private val originalLineSpacingMultiplier = source.lineSpacingMultiplier
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = readingTextPx
        typeface = source.typeface
        color = android.graphics.Color.rgb(110, 110, 110)
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
            typefaceIdentity = source.typeface,
            contentWidth = (source.width - source.compoundPaddingLeft -
                source.compoundPaddingRight).coerceAtLeast(0),
            compoundPaddingLeft = source.compoundPaddingLeft,
            compoundPaddingRight = source.compoundPaddingRight,
            letterSpacing = source.letterSpacing,
            textScaleX = source.textScaleX,
        )
    }

    private val originalGeometry = requireNotNull(currentGeometry()) {
        "Native soft-wrap reading requires a measured source layout"
    }
    /** Same source + exact UTF-16 Layout boundaries as the slow snapshot
     * comparison, but reads current properties directly without allocating. */
    private fun matchesInstalledGeometry(native: Layout): Boolean =
        native.lineCount == rows.size &&
            source.width == originalGeometry.width &&
            (source.width - source.compoundPaddingLeft - source.compoundPaddingRight)
                .coerceAtLeast(0) == originalGeometry.contentWidth &&
            source.compoundPaddingLeft == originalGeometry.compoundPaddingLeft &&
            source.compoundPaddingRight == originalGeometry.compoundPaddingRight &&
            source.typeface == originalGeometry.typefaceIdentity &&
            abs(source.textSize - originalGeometry.textSizePx) < 0.5f &&
            abs(source.letterSpacing - originalGeometry.letterSpacing) < 0.001f &&
            abs(source.textScaleX - originalGeometry.textScaleX) < 0.001f &&
            abs(source.lineSpacingExtra -
                (originalLineSpacingExtra + reservedGapPx)) < 0.5f &&
            abs(source.lineSpacingMultiplier - originalLineSpacingMultiplier) < 0.01f &&
            AppleSoftWrapResumePolicy.sameSourceAndLines(
                savedText = originalGeometry.text,
                currentText = source.text,
                savedLineBounds = originalGeometry.lineBounds,
                currentLineCount = native.lineCount,
                getLineStart = { native.getLineStart(it) },
                getLineEnd = { native.getLineEnd(it) },
            )
    private val paintedRows: List<PaintedRow>
    private val reservedGapPx: Int
    private val ownership = AppleOverlayPaintOwnership()
    private val sourceRect = Rect()
    private val visibleSourceRect = Rect()
    private val inheritedClip = Rect()
    private val appliedClip = Rect()
    private var sampledClip = false
    private var installed = false

    fun bindOwner(check: () -> Boolean) {
        ownership.bind(check)
        if (installed) invalidateSelf()
    }

    init {
        require(rows.size > 1 && rows.indices.all { rows[it].insertAfterSourceCell == it })
        require(measuredWidth > 0 && readingTextPx > 0f)
        paintedRows = rows.dropLast(1).map { row ->
            val variants = AppleInterleavedReadingTimingPolicy.project(
                timedVariants, pronunciation, rows, row, verifiedCrossRow = true,
            )
            val text = SpannableString(row.text)
            val styles = AppleReadingGlyphCoveragePolicy.offsets(row.text, variants).map { offset ->
                val style = AppleReadingKaraokeStyle()
                style.brightness = 0.52f
                text.setSpan(style, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offset to style
            }
            val layout = StaticLayout.Builder.obtain(
                text, 0, text.length, TextPaint(paint), measuredWidth,
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            PaintedRow(
                visualLine = row.insertAfterSourceCell,
                layout = layout,
                styles = styles,
                variantOwners = variants.map { units ->
                    AppleReadingGlyphCoveragePolicy.glyphOwners(row.text, units)
                },
                variantGeometry = variants.map { units ->
                    AppleReadingSweepLayout.measure(row.text, layout, units)
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
    fun resumeIfCompatible(
        currentHolder: ViewGroup?,
        readingAttached: Boolean,
        ownerCurrent: Boolean,
        installWhenReady: Boolean = true,
    ): AppleSoftWrapResumeDecision {
        if (currentHolder !== holderRoot) {
            clear()
            return AppleSoftWrapResumeDecision.REBIND
        }
        var parent: View? = source
        while (parent != null && parent !== holderRoot) parent = parent.parent as? View
        if (source.isAttachedToWindow && holderRoot.isAttachedToWindow &&
            parent !== holderRoot
        ) {
            clear()
            return AppleSoftWrapResumeDecision.REBIND
        }
        // No snapshot comparison is possible during a host layout pass.
        // The previous path allocated a String and line-bound Pair list on
        // every active 16-ms pulse just to return WAIT from decision().
        val preflight = AppleSoftWrapResumePolicy.preflight(
            readingAttached = readingAttached,
            sourceAttached = source.isAttachedToWindow,
            holderAttached = holderRoot.isAttachedToWindow &&
                holderRoot.width > 0 && holderRoot.height > 0,
            layoutPending = source.isLayoutRequested || holderRoot.isLayoutRequested,
            ownerCurrent = ownerCurrent,
        )
        if (preflight != null) {
            if (preflight == AppleSoftWrapResumeDecision.REBIND ||
                !readingAttached || !source.isAttachedToWindow ||
                !holderRoot.isAttachedToWindow
            ) clear()
            return preflight
        }
        if (AppleSoftWrapResumePolicy.fastInstalled(
                installed = installed,
                readingAttached = readingAttached,
                sourceAttached = source.isAttachedToWindow,
                holderAttached = holderRoot.isAttachedToWindow &&
                    holderRoot.width > 0 && holderRoot.height > 0,
                layoutPending = source.isLayoutRequested || holderRoot.isLayoutRequested,
                ownerCurrent = ownerCurrent,
        ) {
                source.layout?.let { matchesInstalledGeometry(it) } == true
        }) return AppleSoftWrapResumeDecision.RESUME
        // An installed overlay owns an extra line-spacing slot. Compare with
        // that expected spacing, not the pre-install value from the snapshot.
        val expected = if (installed) originalGeometry.copy(
            lineSpacingExtra = originalLineSpacingExtra + reservedGapPx,
        ) else originalGeometry
        val decision = AppleSoftWrapResumePolicy.decision(
            saved = expected,
            current = currentGeometry(),
            readingAttached = readingAttached,
            sourceAttached = source.isAttachedToWindow,
            holderAttached = holderRoot.isAttachedToWindow &&
                holderRoot.width > 0 && holderRoot.height > 0,
            layoutPending = source.isLayoutRequested || holderRoot.isLayoutRequested,
            ownerCurrent = ownerCurrent,
        )
        if (decision == AppleSoftWrapResumeDecision.REBIND) {
            clear()
            return decision
        }
        if (decision == AppleSoftWrapResumeDecision.WAIT) {
            if (!readingAttached || !source.isAttachedToWindow ||
                !holderRoot.isAttachedToWindow
            ) clear()
            return decision
        }
        if (installed || !installWhenReady) return AppleSoftWrapResumeDecision.RESUME
        if (parent !== holderRoot || !install()) {
            clear()
            return AppleSoftWrapResumeDecision.REBIND
        }
        return AppleSoftWrapResumeDecision.RESUME
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
        ownership.isCurrent() && source.isShown &&
        source.getGlobalVisibleRect(visibleSourceRect)

    /** Shared final-fragment draw gate. The earlier inline fragments already
     * check these exact live raw UTF-16 characters and native Layout lines;
     * the last Hangul foreground must not keep drawing an older lyric while
     * the same holder awaits its posted replacement bind. No snapshots. */
    fun sourceGeometryCurrentForPaint(): Boolean = installed && ownership.isCurrent() &&
        !source.isLayoutRequested &&
        source.layout?.let(::matchesInstalledGeometry) == true

    fun refresh(positionMs: Long?) {
        if (!installed || !ownership.isCurrent()) return
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
            val geometry = variant?.let { row.variantGeometry.getOrNull(it)?.get(offset) }
            val progress = positionMs?.let {
                owner?.let { glyph ->
                    val wordProgress = AppleTimedPronunciationHighlightPolicy.progress(it, glyph.unit)
                    if (glyph.unit.isWholeLine) wordProgress else geometry?.let { metrics ->
                        AppleReadingSweepGeometry.glyphCoverage(
                            wordProgress, metrics.unitStartPx, metrics.glyphWidthPx,
                            metrics.unitWidthPx,
                        )
                    } ?: 0f
                }
            } ?: 0f
            val brightness = AppleHangulReadingPaintPolicy.timedGlyphBrightness(
                activeVariant = variant != null,
                glyphHasTimingOwner = owner != null,
            )
            if (style.measured != geometry ||
                style.coarseLineClock != (owner?.unit?.isWholeLine == true)
            ) {
                style.measured = geometry
                style.coarseLineClock = owner?.unit?.isWholeLine == true
                changed = true
            }
            if (abs(style.progress - progress) > 0.01f ||
                abs(style.brightness - brightness) > 0.01f ||
                abs(style.opacity - nativeOpacity) > 0.01f
            ) {
                style.progress = progress
                style.brightness = brightness
                style.opacity = nativeOpacity
                changed = true
            }
        }
        if (changed) invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!installed || !ownership.isCurrent() || source.isLayoutRequested ||
            !source.isAttachedToWindow || !source.isShown ||
            !holderRoot.isAttachedToWindow || holderRoot.width <= 0 || holderRoot.height <= 0
        ) return
        val native = source.layout ?: return
        if (!matchesInstalledGeometry(native)) return
        val rect = sourceRect.apply { set(0, 0, source.width, source.height) }
        if (runCatching { holderRoot.offsetDescendantRectToMyCoords(source, rect) }.isFailure)
            return
        if (bounds.width() != holderRoot.width || bounds.height() != holderRoot.height)
            setBounds(0, 0, holderRoot.width, holderRoot.height)
        val inheritedClipVisible = !sampledClip && canvas.getClipBounds(inheritedClip)
        val clipped = canvas.save()
        canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
        val sampleAll = inheritedClipVisible && canvas.getClipBounds(appliedClip)
        var firstInkTop = Int.MAX_VALUE
        var lastInkBottom = Int.MIN_VALUE
        for (row in paintedRows) {
            val sourceBaseline = native.getLineBaseline(row.visualLine) +
                AppleOverlayMappedTextOrigin.top(rect.top, source.totalPaddingTop)
            val inkBottom = sourceBaseline + source.paint.fontMetrics.descent
            val x = AppleOverlayMappedTextOrigin.left(rect.left, source.compoundPaddingLeft) +
                native.getLineLeft(row.visualLine)
            val y = inkBottom + readingTextPx * 0.12f
            if (sampleAll && row.layout.lineCount > 0) {
                val metrics = row.layout.paint.fontMetricsInt
                firstInkTop = minOf(firstInkTop, floor(
                    y + row.layout.getLineBaseline(0) + metrics.top,
                ).toInt())
                lastInkBottom = maxOf(lastInkBottom, ceil(
                    y + row.layout.getLineBaseline(row.layout.lineCount - 1) + metrics.bottom,
                ).toInt())
            }
            val saved = canvas.save()
            canvas.translate(x, y)
            row.layout.draw(canvas)
            canvas.restoreToCount(saved)
        }
        if (sampleAll && firstInkTop != Int.MAX_VALUE && lastInkBottom != Int.MIN_VALUE) {
            sampledClip = true
            val added = AppleOverlayClipPolicy.addedInkLoss(
                firstInkTop, lastInkBottom,
                inheritedClip.top, inheritedClip.bottom,
                appliedClip.top, appliedClip.bottom,
            )
            var transformedAncestors = 0
            var ancestor: View? = source
            while (ancestor != null && ancestor !== holderRoot) {
                if (abs(ancestor.translationX) > 0.01f ||
                    abs(ancestor.translationY) > 0.01f ||
                    abs(ancestor.scaleX - 1f) > 0.01f ||
                    abs(ancestor.scaleY - 1f) > 0.01f ||
                    abs(ancestor.rotation) > 0.01f
                ) transformedAncestors++
                ancestor = ancestor.parent as? View
            }
            val summary = "hostH=${holderRoot.height} " +
                "canvasBeforeTop=${inheritedClip.top} " +
                "canvasBeforeBottom=${inheritedClip.bottom} " +
                "canvasAfterTop=${appliedClip.top} " +
                "canvasAfterBottom=${appliedClip.bottom} " +
                "inkTop=$firstInkTop inkBottom=$lastInkBottom " +
                "manualLostTop=${added.topPx} manualLostBottom=${added.bottomPx} " +
                "transformedAncestors=$transformedAncestors"
            holderRoot.post {
                AppleLyricUiRuntimeProbe.record("softwrap_paint_clip", summary, holderRoot)
            }
        }
        canvas.restoreToCount(clipped)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
