package io.github.proify.lyricon.amprovider.xposed

import android.os.Build
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.graphics.text.LineBreaker
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.TypedValue

/** Immutable song/revision-scoped request. The Japanese native timestamps are never edited. */
internal data class AppleLineWidePronunciationPayload(
    val songId: String,
    val lineKey: String,
    val sourceText: String,
    val pronunciation: String,
    val revision: Long,
    val sourcePriority: Int = 0,
    val lineBeginMs: Long? = null,
    val lineEndMs: Long? = null,
    val timedWords: List<AppleTimedSourceWord> = emptyList(),
    val phoneticSpans: List<ApplePronunciationSourceToken> = emptyList(),
    val repeatTimings: List<List<AppleTimedSourceWord>> = emptyList(),
    val nativeDisplay: AppleNativeReadingSourceDisplay? = null,
) {
    val rawSourceKey = AppleLineWidePronunciationRowPolicy.sourceKey(sourceText)
    val displayedSourceText: String get() = nativeDisplay?.text ?: sourceText
}

/** Each entry is one real source word and its host display form, excluding
 * unspoken separators. Only whole verified words can cross into a raw span map. */
@ConsistentCopyVisibility
internal data class AppleNativeReadingSourceDisplay private constructor(
    private val words: List<Pair<String, String>>,
) {
    val text = words.joinToString("") { it.second }

    fun rawCellTexts(cells: List<String>): List<String>? {
        var word = 0
        val rawCells = cells.map { cell ->
            val display = AppleLineWidePronunciationRowPolicy.sourceKey(cell)
            var offset = 0
            val raw = StringBuilder()
            while (offset < display.length) {
                val (source, shown) = words.getOrNull(word) ?: return null
                if (!display.startsWith(shown, offset)) return null
                raw.append(source)
                offset += shown.length
                word++
            }
            raw.toString()
        }
        return rawCells.takeIf { word == words.size }
    }

    companion object {
        fun fromWords(
            source: String, rawWords: List<String>, transform: (String) -> String,
        ): AppleNativeReadingSourceDisplay? {
            val sourceKey = AppleLineWidePronunciationRowPolicy::sourceKey
            if (sourceKey(source).isEmpty() ||
                sourceKey(rawWords.joinToString("")) != sourceKey(source)
            ) return null
            val words = rawWords.mapNotNull { word ->
                val raw = sourceKey(word)
                val shown = sourceKey(transform(word))
                if (raw.isEmpty() != shown.isEmpty()) return null
                if (raw.isEmpty()) null else raw to shown
            }
            return if (words.any { it.first != it.second }) AppleNativeReadingSourceDisplay(words)
                else null
        }
    }
}

/** Only native Japanese source measurements; injected readings are excluded. */
internal data class AppleNativeSourceMeasureKey(
    val flexWidth: Int,
    val contentWidth: Int,
    val sourceCellWidths: List<Int>,
    val sourceTextSizes: List<Float>,
)

internal object AppleLineWidePronunciationRowPolicy {
    /** A source key alone cannot identify which repeated chorus row owns a
     * timing window; only the native holder-bound plan may render that case. */
    fun safeSourceFallback(payload: AppleLineWidePronunciationPayload): Boolean =
        payload.repeatTimings.isEmpty()

    /** The holder's line clock belongs only to its first occurrence. Repeated
     * chorus variants have distinct native word clocks, not this line clock. */
    fun lineWindowForVariant(
        payload: AppleLineWidePronunciationPayload,
        variantIndex: Int,
    ): Pair<Long?, Long?> = if (variantIndex == 0)
        payload.lineBeginMs to payload.lineEndMs else null to null

    fun canReuseReading(
        boundPayload: AppleLineWidePronunciationPayload?,
        requestedPayload: AppleLineWidePronunciationPayload,
        oldWords: List<*>,
        newWords: List<*>,
        currentIndex: Int,
        insertionIndex: Int,
    ): Boolean = boundPayload == requestedPayload && currentIndex == insertionIndex &&
        oldWords.size == newWords.size &&
        oldWords.indices.all { oldWords[it] === newWords[it] }

    /** Native karaoke words sometimes include a line break between source characters. */
    fun sourceKey(text: String): String = text.filter(Char::isLetterOrDigit)

    fun readableText(value: String?): String? = value?.trim()
        ?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotEmpty)

    /** Match the actual Japanese source prefix, never the subsequent translation cells. */
    fun insertionIndex(sourceText: String, directChildSourceTexts: List<String>): Int? {
        val source = sourceKey(sourceText)
        if (source.isEmpty()) return null
        val observed = StringBuilder(source.length)
        directChildSourceTexts.forEachIndexed { index, text ->
            val letters = sourceKey(text)
            if (letters.isEmpty()) return@forEachIndexed
            observed.append(letters)
            if (!source.startsWith(observed.toString())) return null
            if (observed.toString() == source) {
                var next = index + 1
                // A Japanese punctuation-only cell belongs above the reading.
                while (next < directChildSourceTexts.size &&
                    directChildSourceTexts[next].isNotEmpty() &&
                    sourceKey(directChildSourceTexts[next]).isEmpty()
                ) next++
                return next
            }
        }
        return null
    }

    /** A matching prefix is not enough: the next cell might continue the Japanese lyric. */
    fun verifiedInsertionIndex(sourceText: String, directChildSourceTexts: List<String>): Int? {
        val index = insertionIndex(sourceText, directChildSourceTexts) ?: return null
        // The remaining cells may be translations in any language. Japanese
        // continuation makes the candidate ambiguous, so never attach a short
        // cached reading inside another line. Fail closed for Japanese-language
        // translations rather than displaying a mismatched pronunciation.
        if (directChildSourceTexts.drop(index).any { text ->
                text.any { char ->
                    char.code in 0x3040..0x30FF || char.code in 0x3400..0x9FFF ||
                        char.code in 0xF900..0xFAFF || char.code in 0xFF66..0xFF9F
                }
            }
        ) return null
        return index
    }

    fun shouldDisplay(
        payload: AppleLineWidePronunciationPayload?,
        currentSongId: String?,
        currentRevision: Long,
        visible: Boolean,
        currentSourcePriority: Int,
    ): Boolean = visible && payload != null && payload.songId == currentSongId &&
        payload.revision == currentRevision && payload.sourcePriority == currentSourcePriority &&
        readableText(payload.pronunciation) != null

    /** Native Apple pronunciation is scaled proportionally to its own source. */
    fun readingTextPx(sourcePx: Float): Float = sourcePx * 0.90f

    /** Negative width uses MATCH_PARENT until the flexbox has been laid out. */
    fun fullWidth(measuredWidth: Int): Int = measuredWidth.takeIf { it > 0 } ?: -1

    /** A provisional whole block is especially disruptive when it occupies
     * several rows and is immediately replaced by interleaved children. */
    fun deferUntilMeasured(
        verifiedSourceSpans: Boolean,
        geometryPending: Boolean,
        hasCompatibleExistingRows: Boolean,
    ): Boolean = verifiedSourceSpans && geometryPending && !hasCompatibleExistingRows

    /** Injected full-width reading rows themselves force flex wrapping. A
     * viewport width change must remove those artificial break boundaries
     * before measuring the Japanese-only layout for a new projection. */
    fun needsSourceOnlyRemeasure(
        observedWidths: List<Int>,
        currentWidth: Int,
        hasVerifiedProjection: Boolean,
    ): Boolean = hasVerifiedProjection && currentWidth > 0 &&
        observedWidths.any { it != currentWidth }

    fun measurementChanged(
        previous: List<AppleNativeSourceMeasureKey?>,
        current: AppleNativeSourceMeasureKey,
        hasVerifiedProjection: Boolean,
    ): Boolean = hasVerifiedProjection && current.flexWidth > 0 &&
        previous.any { it != current }

    /** Correct a small line overflow so one stray Hangul syllable does not
     * wrap under its own Japanese source row. Large overflows still wrap at
     * Android's natural breakpoints instead of becoming illegibly tiny. */
    fun fitReadingTextPx(
        targetPx: Float,
        currentPx: Float,
        availablePx: Float,
        measuredTextPx: Float,
    ): Float? {
        if (targetPx <= 0f || currentPx <= 0f || availablePx <= 0f ||
            measuredTextPx <= availablePx || measuredTextPx <= 0f
        ) return null
        val fitted = currentPx * (availablePx * 0.975f / measuredTextPx)
        return fitted.takeIf { it >= targetPx * 0.78f && it < currentPx - 0.5f }
    }
}

/** Pre-wake only the visible upcoming lyric, keeping unrelated rows at low rate. */
internal object AppleReadingPulsePolicy {
    /** The next *word* may begin after a real gap within an already-started
     * line. Looking only at each variant's first timestamp misses that wake,
     * leaving the Korean reading behind the native Japanese karaoke paint.
     * This scans only verified native unit starts; no time is interpolated. */
    fun nextUpcomingBeginMs(
        positionMs: Long,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Long? {
        var nearest: Long? = null
        for (variant in variants) for (unit in variant) {
            val start = unit.beginMs
            if (start >= positionMs && (nearest == null || start < nearest)) {
                nearest = start
            }
        }
        return nearest
    }

    fun nextDelayMs(
        onScreen: Boolean,
        positionMs: Long?,
        nextBeginMs: Long?,
        active: Boolean,
    ): Long = if (onScreen && (active ||
            (positionMs != null && nextBeginMs != null &&
                nextBeginMs - positionMs in 0L..450L))) 60L else 400L

    /** Off-screen holders must not query Exo's playback clock. The caller
     * reuses this one sample for its paint and next scheduler wake. */
    fun playbackSample(onScreen: Boolean, clock: (() -> Long?)?): Long? =
        if (onScreen) clock?.invoke() else null

    /** The same player position drives both highlight and scheduler wake. */
    fun nextDelayForReading(
        onScreen: Boolean,
        positionMs: Long?,
        variants: List<List<AppleTimedReadingUnit>>,
    ): Long {
        if (!onScreen) return 400L
        val position = positionMs ?: return 400L
        val active = AppleTimedPronunciationHighlightPolicy.activeVariant(
            position, variants,
        ) != null
        val upcoming = if (active) null else nextUpcomingBeginMs(position, variants)
        return nextDelayMs(onScreen, position, upcoming, active)
    }
}

/** An accepted native Exo seek may jump past the slow off-interval poll.
 * Wake only the same song/revision's attached, visible fallback reading. */
internal object AppleReadingSeekWakePolicy {
    fun shouldWake(
        boundSongId: String?, seekSongId: String?,
        boundRevision: Long, currentRevision: Long,
        attached: Boolean, onScreen: Boolean,
    ): Boolean = !seekSongId.isNullOrBlank() && boundSongId == seekSongId &&
        boundRevision == currentRevision && attached && onScreen
}

/** Different chorus variants can associate a timing with different-sized
 * pronunciation groups. Paint each *actual syllable* rather than keeping only
 * the first variant's token start (which silently leaves trailing syllables
 * without their own style). */
internal object AppleReadingGlyphCoveragePolicy {
    data class GlyphOwner(
        val unit: AppleTimedReadingUnit,
        val ordinal: Int,
        val count: Int,
    )

    fun offsets(
        value: String,
        variants: List<List<AppleTimedReadingUnit>>,
    ): List<Int> = variants.asSequence().flatten().flatMap { unit ->
        (unit.start until unit.end).asSequence()
    }.filter { it in value.indices && value[it].isLetterOrDigit() }
        .distinct().sorted().toList()

    fun owners(units: List<AppleTimedReadingUnit>): Map<Int, AppleTimedReadingUnit> {
        val mapped = mutableMapOf<Int, AppleTimedReadingUnit>()
        val conflicts = mutableSetOf<Int>()
        units.forEach { unit ->
            for (offset in unit.start until unit.end) {
                if (offset in conflicts) continue
                if (mapped.put(offset, unit) != null) {
                    mapped.remove(offset)
                    conflicts += offset
                }
            }
        }
        return mapped
    }

    /** Precompute each syllable's word position once at bind time. */
    fun glyphOwners(
        value: String,
        units: List<AppleTimedReadingUnit>,
    ): Map<Int, GlyphOwner> {
        val mapped = mutableMapOf<Int, GlyphOwner>()
        val conflicts = mutableSetOf<Int>()
        units.forEach { unit ->
            val glyphs = (unit.start until unit.end).filter {
                it in value.indices && value[it].isLetterOrDigit()
            }
            glyphs.forEachIndexed { ordinal, offset ->
                if (offset in conflicts) return@forEachIndexed
                if (mapped.put(offset, GlyphOwner(unit, ordinal, glyphs.size)) != null) {
                    mapped.remove(offset)
                    conflicts += offset
                }
            }
        }
        return mapped
    }

    /** Only a verified *word* gets a left-to-right karaoke sweep. A native
     * whole-line fallback has no syllable timing, so keep its uniform clock. */
    fun glyphProgress(owner: GlyphOwner, positionMs: Long): Float {
        val progress = AppleTimedPronunciationHighlightPolicy.progress(positionMs, owner.unit)
        if (owner.unit.isWholeLine) return progress
        return (progress * owner.count - owner.ordinal).coerceIn(0f, 1f)
    }

    fun progressAtGlyph(
        value: String, unit: AppleTimedReadingUnit, glyphOffset: Int, positionMs: Long,
    ): Float = glyphOwners(value, listOf(unit))[glyphOffset]?.let {
        glyphProgress(it, positionMs)
    } ?: 0f
}

/**
 * Attached to Apple's measured karaoke_line_flexbox, not to any timed native word.
 * The flexbox wraps this full-width child below the intact native Japanese line.
 * Only observed Apple Music 6.5.3 view anchors are accepted; unfamiliar hosts fail closed.
 */
internal class AppleLineWidePronunciationRow {
    /** Weak keys: an old RecyclerView holder must not stay alive via the hook. */
    private val readingViews = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ReadingView, Boolean>(),
    )

    /** A single styled Hangul syllable, dim until its own Apple-timed interval. */
    private class KaraokeSyllableStyle : CharacterStyle(), UpdateAppearance {
        var progress = 0f
        var nativeBrightness = 1f

        override fun updateDrawState(paint: TextPaint) {
            val dim = 0.56f
            val blend = (nativeBrightness * (dim + (1f - dim) * progress)).coerceIn(0f, 1f)
            paint.color = Color.rgb(
                (Color.red(Color.WHITE) * blend).toInt(),
                (Color.green(Color.WHITE) * blend).toInt(),
                (Color.blue(Color.WHITE) * blend).toInt(),
            )
        }
    }

    // This view is injected into Apple Music's classloader and must depend only
    // on platform Android widgets; requiring AppCompat could break NPatch Local.
    @SuppressLint("AppCompatCustomView")
    private class ReadingView(context: android.content.Context) : TextView(context) {
        var playbackPositionMs: (() -> Long?)? = null
        var sourceWords: List<TextView> = emptyList()
        var boundPayload: AppleLineWidePronunciationPayload? = null
        var sourceCellAbove: View? = null
        var readingStart = 0
        var readingEnd = 0
        var measuredSourceWidth = 0
        var sourceMeasureKey: AppleNativeSourceMeasureKey? = null
        var targetReadingTextPx = 0f
        var inlineWrapOverlay: AppleNativeSoftWrapReadingOverlay? = null
        var inlineVisualRows: List<AppleInterleavedReadingRow>? = null
        var isCurrentBinding: () -> Boolean = { true }
        var onNeedsRebind: (() -> Unit)? = null
        private var rebindScheduled = false
        private var paintRoot: ViewGroup? = null
        private var outerForeground: AppleHangulReadingForeground? = null
        private val updatePaintLayer = Runnable { syncPaintLayer() }
        private var timedStyles: List<Pair<Int, KaraokeSyllableStyle>> = emptyList()
        private var plainReading = ""
        private var timedVariants: List<List<AppleTimedReadingUnit>> = emptyList()
        private var timedVariantLookups: List<Map<Int, AppleReadingGlyphCoveragePolicy.GlyphOwner>> =
            emptyList()
        private val visibleBounds = Rect()
        private val highlightPulse = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                if (!bindingCurrent()) {
                    syncPaintLayer()
                    return
                }
                syncPaintLayer()
                val onScreen = (isShown && getGlobalVisibleRect(visibleBounds)) ||
                    inlineWrapOverlay?.isSourceVisibleOnScreen() == true
                val position = AppleReadingPulsePolicy.playbackSample(
                    onScreen, playbackPositionMs,
                )
                if (onScreen) syncTimedHighlight(position)
                // Apple's karaoke line may animate from its own RenderNode.
                // Refresh the per-syllable paint from the actual playback clock.
                // Never poll unrelated/off-screen lines at animation rate.
                // Native word cells own fast karaoke animation; this fallback
                // is only a last-resort line-wide display.
                postDelayed(this, AppleReadingPulsePolicy.nextDelayForReading(
                    onScreen = onScreen,
                    positionMs = position,
                    variants = timedVariants,
                ))
            }
        }

        fun refreshForAcceptedSeek(songId: String?, currentRevision: Long): Boolean {
            val onScreen = (isAttachedToWindow && isShown &&
                getGlobalVisibleRect(visibleBounds)) ||
                inlineWrapOverlay?.isSourceVisibleOnScreen() == true
            if (!AppleReadingSeekWakePolicy.shouldWake(
                    boundSongId = boundPayload?.songId,
                    seekSongId = songId,
                    boundRevision = boundPayload?.revision ?: -1L,
                    currentRevision = currentRevision,
                    attached = isAttachedToWindow,
                    onScreen = onScreen,
            )) return false
            // Coalesce consecutive seeks and cancel a stale idle pulse. The
            // pulse samples the accepted player clock rather than inventing
            // a position from the clicked row's timestamps.
            removeCallbacks(highlightPulse)
            post(highlightPulse)
            return true
        }

        fun setTimedText(value: String, variants: List<List<AppleTimedReadingUnit>>) {
            plainReading = value
            val styled = SpannableString(value)
            timedVariants = variants.map { units ->
                units.filter { it.start >= 0 && it.end <= value.length && it.start < it.end }
            }
            timedVariantLookups = timedVariants.map { units ->
                AppleReadingGlyphCoveragePolicy.glyphOwners(value, units)
            }
            timedStyles = AppleReadingGlyphCoveragePolicy.offsets(value, timedVariants)
                .map { offset ->
                    val style = KaraokeSyllableStyle()
                    styled.setSpan(style, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    offset to style
                }
            setText(styled, BufferType.SPANNABLE)
            syncTimedHighlight()
        }

        fun bindPaintRoot(root: View?) {
            val next = root as? ViewGroup
            if (paintRoot !== next) {
                detachOuterForeground()
                paintRoot = next
            }
            removeCallbacks(updatePaintLayer)
            post(updatePaintLayer)
        }

        private fun bindingCurrent(): Boolean = runCatching {
            isCurrentBinding()
        }.getOrDefault(false)

        private fun detachOuterForeground() {
            outerForeground?.let { foreground ->
                paintRoot?.overlay?.remove(foreground)
            }
            outerForeground = null
            if (kotlin.math.abs(alpha - 1f) > 0.01f) alpha = 1f
        }

        private fun syncPaintLayer() {
            val root = paintRoot ?: return
            if (!bindingCurrent()) {
                inlineWrapOverlay?.clear()
                detachOuterForeground()
                alpha = 0f
                return
            }
            // The outer ViewOverlay and the extra Japanese line spacing were
            // removed on detach. A normal viewport reattach does not
            // necessarily trigger another holder bind, so recover the same
            // measured overlay here without guessing new phonetic boundaries.
            val resume = inlineWrapOverlay?.resumeIfCompatible(
                root, readingAttached = isAttachedToWindow, ownerCurrent = true,
            )
            if (resume == AppleSoftWrapResumeDecision.REBIND) {
                detachOuterForeground()
                alpha = 0f
                if (!rebindScheduled) {
                    rebindScheduled = true
                    post {
                        if (!isAttachedToWindow || !root.isAttachedToWindow) {
                            rebindScheduled = false
                            return@post
                        }
                        if (bindingCurrent()) onNeedsRebind?.invoke()
                    }
                }
                return
            }
            val measured = isAttachedToWindow && isShown && root.isAttachedToWindow &&
                layout != null && width > 0 && height > 0 &&
                root.width > 0 && root.height > 0
            if (!measured && outerForeground != null) detachOuterForeground()
            var newForeground = false
            if (measured && outerForeground == null) {
                val foreground = AppleHangulReadingForeground(root, this)
                foreground.syncBounds()
                if (runCatching { root.overlay.add(foreground) }.isSuccess) {
                    outerForeground = foreground
                    newForeground = true
                }
            }
            outerForeground?.syncBounds()
            val owners = AppleHangulReadingPaintPolicy.owners(
                rootAttached = isAttachedToWindow && root.isAttachedToWindow,
                rootWidth = root.width,
                rootHeight = root.height,
                readingWidth = width,
                readingHeight = height,
                overlayAttached = outerForeground != null,
            )
            val targetAlpha = if (owners.nativeVisible) 1f else 0f
            if (kotlin.math.abs(alpha - targetAlpha) > 0.01f) alpha = targetAlpha
            // Only timing-style changes and an initial attach require paint.
            // Invalidating the same overlay on every idle poll causes jank.
            if (newForeground) {
                // A bounded, text-free sample of the *actual measured host*
                // geometry. #234 shows Japanese visual wraps followed by a
                // block of readings, but a silent video cannot distinguish
                // one wrapped holder from several independently bound rows.
                // These positions and font metrics allow a future NPatch log
                // to discriminate that without guessing Japanese↔Hangul cuts.
                AppleLyricUiRuntimeProbe.recordLazy("linewide_measured_geometry", root) {
                    val readingRect = Rect(0, 0, width, height)
                    val positioned = runCatching {
                        root.offsetDescendantRectToMyCoords(this, readingRect)
                    }.isSuccess
                    val sourceTops = sourceWords.mapNotNull { word ->
                        if (!word.isAttachedToWindow || word.width <= 0 || word.height <= 0)
                            return@mapNotNull null
                        val rect = Rect(0, 0, word.width, word.height)
                        if (runCatching {
                                root.offsetDescendantRectToMyCoords(word, rect)
                            }.isSuccess
                        ) rect.top else null
                    }
                    val lines = layout?.lineCount ?: 0
                    val fm = paint.fontMetricsInt
                    val firstInkTop = if (positioned && lines > 0)
                        readingRect.top + totalPaddingTop +
                            layout!!.getLineBaseline(0) + fm.top else null
                    val lastInkBottom = if (positioned && lines > 0)
                        readingRect.top + totalPaddingTop +
                            layout!!.getLineBaseline(lines - 1) + fm.bottom else null
                    val sourceTopSpan = if (sourceTops.isNotEmpty())
                        sourceTops.max() - sourceTops.min() else -1
                    val sourceLineCounts = sourceWords.map { it.layout?.lineCount ?: 0 }
                    val wrappedSourceViews = sourceLineCounts.count { it > 1 }
                    val maxSourceLineCount = sourceLineCounts.maxOrNull() ?: 0
                    "hostW=${root.width} hostH=${root.height} " +
                        "flexW=${(parent as? View)?.width ?: -1} " +
                        "readingW=$width readingH=$height readingTop=${if (positioned) readingRect.top else -1} " +
                        "readingLines=$lines firstInkTop=${firstInkTop ?: -1} " +
                        "lastInkBottom=${lastInkBottom ?: -1} " +
                        "sourceWords=${sourceWords.size} sourceTopSpan=$sourceTopSpan " +
                        "wrappedSourceViews=$wrappedSourceViews maxSourceLines=$maxSourceLineCount"
                }
                outerForeground?.invalidateSelf()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(highlightPulse)
            post(highlightPulse)
            removeCallbacks(updatePaintLayer)
            post(updatePaintLayer)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed) {
                removeCallbacks(updatePaintLayer)
                post(updatePaintLayer)
            }
            if ((layout?.lineCount ?: 0) > 1 && width > 0) {
                val available = (width - compoundPaddingLeft - compoundPaddingRight).toFloat()
                val measured = paint.measureText(plainReading)
                val fitted = AppleLineWidePronunciationRowPolicy.fitReadingTextPx(
                    targetPx = targetReadingTextPx,
                    currentPx = textSize,
                    availablePx = available,
                    measuredTextPx = measured,
                )
                if (fitted != null) setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(highlightPulse)
            removeCallbacks(updatePaintLayer)
            detachOuterForeground()
            inlineWrapOverlay?.clear()
            rebindScheduled = false
            // Keep the immutable source/row snapshot for a temporary viewport
            // detach. An actual holder rebind calls clear(root), which still
            // disposes the old overlay before inserting a different reading.
            super.onDetachedFromWindow()
        }

        private fun syncTimedHighlight(
            positionMs: Long? = playbackPositionMs?.invoke(),
        ) {
            // The source line's view opacity also fades as Apple Music scrolls.
            // Share that line-level fade without copying any word's *single*
            // highlight color onto the entire Korean sentence.
            val parentAnchor = parent as? View
            val nativeOpacities = sourceWords.map { source ->
                // Preserve source word indexes even if one TextView is
                // temporarily detached during a RecyclerView animation.
                if (!source.isAttachedToWindow || parentAnchor == null) return@map null
                var opacity = 1f
                var view: View? = source
                while (view != null && view !== parentAnchor) {
                    opacity *= view.alpha
                    view = view.parent as? View
                }
                if (view !== parentAnchor) return@map null
                // Apple's visible karaoke gradient may be painted by a
                // RenderNode/parent mask rather than TextView.currentTextColor.
                // Multiplying by the stale base color left the Korean syllable
                // grey while the matching Japanese word was fully lit.
                opacity
            }
            val defaultNativeOpacity = nativeOpacities.filterNotNull().maxOrNull() ?: 1f
            // The native flexbox measures this view but an outer holder
            // foreground paints its Layout outside the host's alpha mask.
            val activeVariantIndex = positionMs?.let {
                AppleTimedPronunciationHighlightPolicy.activeVariant(it, timedVariants)
            }
            val activeVariant = activeVariantIndex?.let(timedVariantLookups::get)
            var changed = false
            timedStyles.forEach { (offset, style) ->
                val glyphOwner = activeVariant?.get(offset)
                val nativeWord = glyphOwner?.unit
                val next = positionMs?.let { now ->
                    glyphOwner?.let { owner ->
                        AppleReadingGlyphCoveragePolicy.glyphProgress(owner, now)
                    }
                } ?: 0f
                if (kotlin.math.abs(style.progress - next) > 0.01f) {
                    style.progress = next
                    changed = true
                }
                // Do not borrow white/bright source text from a non-playing
                // line (the parent native view may still be fully opaque).
                val nativeBrightness = if (activeVariantIndex == null) 0.52f else
                    nativeWord?.sourceWordIndex
                        ?.takeIf { it in nativeOpacities.indices }
                        ?.let(nativeOpacities::get) ?: defaultNativeOpacity
                if (kotlin.math.abs(style.nativeBrightness - nativeBrightness) > 0.01f) {
                    style.nativeBrightness = nativeBrightness
                    changed = true
                }
            }
            if (changed) {
                invalidate()
                outerForeground?.invalidateSelf()
            }
            inlineWrapOverlay?.refresh(positionMs)
        }
    }

    /** Size only Apple-native Hangul readings; never resize Japanese source or
     * translated meaning. The pronunciation container belongs to Apple's own
     * word-level karaoke hierarchy, preserving its highlight animation. */
    fun fitNativeHangulSize(root: View?): Int {
        val parent = anchor(root) ?: return 0
        val views = mutableListOf<TextView>()
        fun collect(view: View?, depth: Int) {
            if (view == null || depth > 8 || view is ReadingView) return
            if (view is TextView) views += view
            else if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1)
            }
        }
        collect(parent, 0)
        fun isUnder(view: View, hint: String): Boolean {
            var current = view.parent as? View
            while (current != null && current !== parent) {
                if (idName(current)?.contains(hint, ignoreCase = true) == true) return true
                current = current.parent as? View
            }
            return false
        }
        val japaneseSource = views.filter { view ->
            !isUnder(view, "translation") &&
                view.text.any { c ->
                    c in '\u3040'..'\u30FF' || c in '\u3400'..'\u9FFF' ||
                        c in '\uF900'..'\uFAFF'
                }
        }
        val sourceSize = japaneseSource.maxOfOrNull { it.textSize } ?: return 0
        val targetSize = AppleLineWidePronunciationRowPolicy.readingTextPx(sourceSize)
        if (targetSize <= 0f) return 0
        var adjusted = 0
        var nativeHangulCount = 0
        var nativeFontPaddingAdded = 0
        var nativeShaderCells = 0
        var nativeUnmeasuredCells = 0
        for (view in views) {
            if (!isUnder(view, "pronunciation") || isUnder(view, "translation") ||
                !view.text.any { it in '가'..'힣' }
            ) continue
            nativeHangulCount++
            if (view.paint.shader != null) nativeShaderCells++
            if (view.layout == null || view.height <= 0) nativeUnmeasuredCells++
            if (!view.includeFontPadding) {
                // The verified native word renderer stays in charge of
                // per-word karaoke timing; only reserve its full glyph ascent
                // and descent, without adding or recoloring another word.
                view.includeFontPadding = true
                nativeFontPaddingAdded++
            }
            if (kotlin.math.abs(view.textSize - targetSize) > 0.8f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetSize)
                adjusted++
            }
        }
        if (nativeHangulCount > 0) AppleLyricUiRuntimeProbe.record(
            "native_reading_paint_path",
            "nativeCells=$nativeHangulCount fontPaddingAdded=$nativeFontPaddingAdded " +
                "shaders=$nativeShaderCells unmeasured=$nativeUnmeasuredCells " +
                "lineWide=${if ((0 until parent.childCount).any {
                    parent.getChildAt(it) is ReadingView
                }) 1 else 0}",
            root,
        )
        // Only Apple's own native word cells paint the verified Hangul path.
        // A second foreground pass bypassed the parent karaoke gradient,
        // caused focused/inactive colors to diverge, and polled each row.
        // Font padding remains measured by Apple; the uncertain line-wide
        // path still uses its exclusive ReadingView paint owner.
        // setTextSize() already requests measurement from the native parent.
        // Forcing an additional parent.requestLayout() and a second
        // root.postOnAnimation(requestLayout) after bind created a two-frame
        // native karaoke gradient reflow: the lower reading glyphs could
        // catch up only when the next lyric RecyclerView item appeared.
        // Keep the 90% source-relative text size; let Apple complete its own
        // single layout pass without any forced follow-up reflow.
        return adjusted
    }

    fun clear(root: View?) {
        val anchor = anchor(root) ?: return
        for (index in anchor.childCount - 1 downTo 0) {
            val reading = anchor.getChildAt(index) as? ReadingView
            if (reading != null) {
                reading.inlineWrapOverlay?.clear()
                reading.inlineWrapOverlay = null
                reading.inlineVisualRows = null
                readingViews.remove(reading)
                anchor.removeViewAt(index)
            }
        }
    }

    /** Called on the UI thread after Exo accepts a song-scoped seek. */
    fun refreshVisibleForAcceptedSeek(songId: String?, currentRevision: Long): Int {
        if (songId.isNullOrBlank()) return 0
        val count = readingViews.size
        val woken = readingViews.count { it.refreshForAcceptedSeek(songId, currentRevision) }
        AppleLyricUiRuntimeProbe.record(
            "linewide_seek_wake", "tracked=$count woken=$woken",
        )
        return woken
    }

    /** Observe only the native source geometry, excluding our injected rows.
     * Include nested source TextViews: their internal wrapping can change even
     * when their parent flexbox retains the same outer dimensions. */
    fun nativeSourceLayoutViews(root: View?): List<View> {
        val flex = anchor(root) ?: return emptyList()
        val source = (0 until flex.childCount).map(flex::getChildAt)
            .filterNot { it is ReadingView }
        return (listOf(flex) + source + source.flatMap(::nativeWordTexts)).distinct()
    }

    /** Source-only fingerprint; a freshly added ReadingView must not itself
     * be mistaken for a changed Japanese line break. */
    fun nativeSourceGeometry(root: View?): List<Int>? {
        val flex = anchor(root) ?: return null
        val source = (0 until flex.childCount).map(flex::getChildAt)
            .filterNot { it is ReadingView }
        return buildList {
            add(flex.width)
            add(flex.paddingLeft)
            add(flex.paddingRight)
            source.forEach { cell ->
                add(System.identityHashCode(cell))
                add(cell.left)
                add(cell.top)
                add(cell.right)
                add(cell.bottom)
                nativeWordTexts(cell).forEach { word ->
                    add(System.identityHashCode(word))
                    add(word.text.toString().hashCode())
                    add(word.textSize.toBits())
                    add(word.width)
                    add(word.height)
                    add(word.compoundPaddingLeft)
                    add(word.compoundPaddingRight)
                    add(word.letterSpacing.toBits())
                    add(word.textScaleX.toBits())
                    add(System.identityHashCode(word.typeface))
                    val lines = word.layout
                    add(lines?.lineCount ?: -1)
                    if (lines != null) for (index in 0 until lines.lineCount) {
                        add(lines.getLineStart(index))
                        add(lines.getLineEnd(index))
                    }
                }
            }
        }
    }

    fun bind(
        root: View?,
        payload: AppleLineWidePronunciationPayload?,
        currentSongId: String?,
        currentRevision: Long,
        visible: Boolean,
        currentSourcePriority: Int,
        trustedHolder: Boolean = false,
        playbackPositionMs: () -> Long? = { null },
        isCurrentBinding: () -> Boolean = { true },
        onNeedsRebind: () -> Unit = {},
    ): Boolean {
        val anchor = anchor(root) ?: return false
        if (!AppleLineWidePronunciationRowPolicy.shouldDisplay(
                payload, currentSongId, currentRevision, visible, currentSourcePriority,
        )) {
            clear(root)
            return false
        }

        // Rebinding may happen after we have already interleaved one or more
        // reading children. They must not count as Apple-native source cells.
        val nativeChildren = (0 until anchor.childCount)
            .map(anchor::getChildAt).filterNot { it is ReadingView }
        val childWords = nativeChildren.map(::nativeWordTexts)
        val childText = childWords.map { words -> words.joinToString("") { it.text.toString() } }
        val insertionIndex = (if (trustedHolder)
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                payload?.displayedSourceText.orEmpty(), childText,
            ) else AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                payload?.displayedSourceText.orEmpty(), childText,
        )) ?: run {
            clear(root)
            return false
        }
        val sourceChildren = nativeChildren.take(insertionIndex)
        val sourceWords = childWords.take(insertionIndex).flatten()
        val sourceStyle = sourceWords.firstOrNull() ?: run {
            clear(root)
            return false
        }
        val text = AppleLineWidePronunciationRowPolicy.readableText(payload?.pronunciation)
            ?: run {
                clear(root)
                return false
            }
        val hasVerifiedProjection = payload!!.phoneticSpans.isNotEmpty() ||
            AppleInterleavedReadingPolicy.canDeriveKanaRows(payload.sourceText, text)
        val nativeMeasureKey = AppleNativeSourceMeasureKey(
            flexWidth = anchor.width,
            contentWidth = (anchor.width - anchor.paddingLeft - anchor.paddingRight)
                .coerceAtLeast(0),
            sourceCellWidths = sourceChildren.map { it.width },
            sourceTextSizes = sourceWords.map { it.textSize },
        )
        val existing = (0 until anchor.childCount)
            .map(anchor::getChildAt).filterIsInstance<ReadingView>()
        if (AppleLineWidePronunciationRowPolicy.needsSourceOnlyRemeasure(
                observedWidths = existing.map(ReadingView::measuredSourceWidth),
                currentWidth = anchor.width,
                hasVerifiedProjection = hasVerifiedProjection,
            ) || AppleLineWidePronunciationRowPolicy.measurementChanged(
                previous = existing.map(ReadingView::sourceMeasureKey),
                current = nativeMeasureKey,
                hasVerifiedProjection = hasVerifiedProjection,
            )
        ) {
            clear(root)
            // The next measured flex layout must contain Japanese cells only.
            // In particular, the prior Hangul full-width children must not
            // keep narrow-screen row boundaries alive on a wider screen.
            anchor.requestLayout()
            return false
        }
        // Use the host's measured flex rows, never a guessed character count.
        // A native TextView that wraps internally cannot be safely divided
        // without replacing Apple's own word/shader renderer.
        val geometryPending = anchor.width <= 0 || sourceChildren.isEmpty() ||
            anchor.isLayoutRequested || root?.isLayoutRequested == true ||
            sourceChildren.any {
                it.isLayoutRequested || it.width <= 0 || it.height <= 0
            } || sourceWords.any { it.isLayoutRequested || it.layout == null }
        // During a pending holder remeasure, old coordinates are stale. Keep
        // the already-bound reading rows temporarily, rather than collapsing
        // them back to one block and triggering another unnecessary reflow.
        val hasCompatibleExistingRows = existing.isNotEmpty() && existing.all { reading ->
                reading.boundPayload == payload &&
                    runCatching { reading.isCurrentBinding() }.getOrDefault(false) &&
                    reading.sourceCellAbove in sourceChildren &&
                    reading.sourceWords.size == sourceWords.size &&
                    sourceWords.indices.all { reading.sourceWords[it] === sourceWords[it] }
            }
        if (geometryPending && hasCompatibleExistingRows) {
            existing.forEach { reading ->
                reading.playbackPositionMs = playbackPositionMs
                reading.isCurrentBinding = isCurrentBinding
                reading.onNeedsRebind = onNeedsRebind
                reading.bindPaintRoot(root)
                readingViews.add(reading)
            }
            return true
        }
        if (AppleLineWidePronunciationRowPolicy.deferUntilMeasured(
                verifiedSourceSpans = hasVerifiedProjection,
                geometryPending = geometryPending,
                hasCompatibleExistingRows = hasCompatibleExistingRows,
            )
        ) {
            clear(root)
            return false
        }
        val displayedCells = childText.take(insertionIndex)
        val nativeDisplay = payload.nativeDisplay
        val rawCells = if (nativeDisplay == null) displayedCells
            else nativeDisplay.rawCellTexts(displayedCells)
        val rows = if (!geometryPending && rawCells != null) AppleInterleavedReadingPolicy.plan(
            sourceText = payload!!.sourceText,
            pronunciation = text,
            cells = sourceChildren.mapIndexed { index, child ->
                AppleVisualSourceCell(
                    text = rawCells[index],
                    top = child.top,
                    bottom = child.bottom,
                    nativeLineCount = childWords[index].maxOfOrNull { it.layout?.lineCount ?: 0 } ?: 0,
                )
            },
            spans = payload.phoneticSpans,
        ) else null
        val targetTextSize = AppleLineWidePronunciationRowPolicy.readingTextPx(sourceStyle.textSize)
        // A flex cell can itself contain several TextView.Layout lines. Flex
        // children cannot be injected BETWEEN those lines. Only for one exact
        // native source TextView, use its REAL UTF-16 soft-line offsets to
        // reserve an inline reading slot without cloning Japanese words or
        // replacing Apple's native shader/click target.
        val softWrapRows = if (!geometryPending && rows == null && root is ViewGroup &&
            sourceChildren.size == 1 && sourceWords.size == 1 &&
            (sourceStyle.layout?.lineCount ?: 0) > 1 &&
            sourceStyle.text.toString() == payload.sourceText
        ) {
            val nativeLayout = requireNotNull(sourceStyle.layout)
            AppleNativeSoftWrapReadingPolicy.plan(
                sourceText = payload.sourceText,
                displayedText = sourceStyle.text.toString(),
                pronunciation = text,
                visualLineRanges = (0 until nativeLayout.lineCount).map { index ->
                    nativeLayout.getLineStart(index) until nativeLayout.getLineEnd(index)
                },
                spans = payload.phoneticSpans,
            )?.takeIf { verified ->
                verified.size == nativeLayout.lineCount &&
                    verified.indices.all { verified[it].insertAfterSourceCell == it }
            }
        } else null
        val timedVariants by lazy {
            (listOf(payload.timedWords) + payload.repeatTimings)
                .mapIndexed { index, native ->
                    val (begin, end) = AppleLineWidePronunciationRowPolicy.lineWindowForVariant(
                        payload, index,
                    )
                    AppleTimedPronunciationHighlightPolicy.units(
                        sourceText = payload.sourceText,
                        pronunciation = text,
                        timedWords = native,
                        phoneticSpans = payload.phoneticSpans,
                        lineBeginMs = begin,
                        lineEndMs = end,
                    )
                }
        }
        val desiredRows = when {
            softWrapRows != null -> listOf(
                softWrapRows.last().copy(insertAfterSourceCell = insertionIndex - 1),
            )
            rows != null -> rows
            else -> listOf(AppleInterleavedReadingRow(
                insertAfterSourceCell = insertionIndex - 1,
                text = text,
                readingStart = 0,
                readingEnd = text.length,
            ))
        }
        val canReuse = existing.size == desiredRows.size &&
            existing.zip(desiredRows).all { (view, row) ->
                val previous = sourceChildren[row.insertAfterSourceCell]
                view.boundPayload == payload && view.sourceCellAbove === previous &&
                    view.readingStart == row.readingStart &&
                    view.readingEnd == row.readingEnd && view.text.toString() == row.text &&
                    view.sourceWords.size == sourceWords.size &&
                    sourceWords.indices.all { view.sourceWords[it] === sourceWords[it] } &&
                    anchor.indexOfChild(view) == anchor.indexOfChild(previous) + 1 &&
                    view.layoutParams?.width ==
                        AppleLineWidePronunciationRowPolicy.fullWidth(nativeMeasureKey.contentWidth) &&
                    view.inlineVisualRows == softWrapRows &&
                    (softWrapRows == null || view.inlineWrapOverlay != null) &&
                    kotlin.math.abs(view.targetReadingTextPx - targetTextSize) < 0.8f &&
                    runCatching { view.isCurrentBinding() }.getOrDefault(false) &&
                    (softWrapRows == null || view.inlineWrapOverlay?.resumeIfCompatible(
                        currentHolder = root as? ViewGroup,
                        readingAttached = view.isAttachedToWindow,
                        ownerCurrent = true,
                        installWhenReady = false,
                    ) != AppleSoftWrapResumeDecision.REBIND)
            }
        if (canReuse) {
            existing.forEach { reading ->
                reading.playbackPositionMs = playbackPositionMs
                reading.isCurrentBinding = isCurrentBinding
                reading.onNeedsRebind = onNeedsRebind
                reading.bindPaintRoot(root)
                readingViews.add(reading)
            }
            return true
        }
        // Never construct a replacement overlay while a preceding overlay's
        // extra TextView line spacing is still installed. Its constructor
        // captures the *current* spacing; doing this first would double the
        // spacing on every rebind and leave the first gap after recycling.
        // Removing the old reading also changes source geometry, so allow one
        // host remeasure before trusting its next internal line offsets.
        if (existing.any { it.inlineWrapOverlay != null }) {
            clear(root)
            anchor.requestLayout()
            return false
        }
        clear(root)
        val softWrapOverlay = softWrapRows?.let { verified ->
            runCatching {
                AppleNativeSoftWrapReadingOverlay(
                    root as ViewGroup, sourceStyle, targetTextSize, text, verified, timedVariants,
                )
            }.getOrNull()?.takeIf { it.install() }
        }
        // Installation can fail on an unfamiliar host. Fall back to the full
        // reading rather than leaving the earlier Hangul line fragments blank.
        val visibleSoftRows = softWrapRows.takeIf { softWrapOverlay != null }
        val committedRows = if (softWrapRows != null && visibleSoftRows == null) listOf(
            AppleInterleavedReadingRow(
                insertAfterSourceCell = insertionIndex - 1,
                text = text,
                readingStart = 0,
                readingEnd = text.length,
            ),
        ) else desiredRows
        val insertionResult = runCatching { committedRows.forEach { row ->
            val precedingNativeCell = sourceChildren[row.insertAfterSourceCell]
            val reading = ReadingView(anchor.context).apply {
                this.playbackPositionMs = playbackPositionMs
                this.isCurrentBinding = isCurrentBinding
                this.onNeedsRebind = onNeedsRebind
                this.sourceWords = sourceWords
                this.boundPayload = payload
                this.sourceCellAbove = precedingNativeCell
                this.readingStart = row.readingStart
                this.readingEnd = row.readingEnd
                this.measuredSourceWidth = anchor.width
                this.sourceMeasureKey = nativeMeasureKey
                this.targetReadingTextPx = targetTextSize
                this.inlineVisualRows = visibleSoftRows
                bindPaintRoot(root)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, targetTextSize)
                // Any syllable with no verified native timing stays visibly dim.
                setTextColor(Color.rgb(110, 110, 110))
                typeface = sourceStyle.typeface
                gravity = Gravity.START
                // The native flexbox measures the layout; the holder overlay
                // repaints the same glyphs without its horizontal gradient mask.
                includeFontPadding = true
                setSingleLine(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    setLineBreakWordStyle(
                        android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE,
                    )
                }
                setTimedText(row.text, AppleInterleavedReadingTimingPolicy.project(
                    variants = timedVariants,
                    fullPronunciation = text,
                    rows = visibleSoftRows ?: committedRows,
                    row = row,
                ))
            }
            // Source rows remain unmodified and in their original order.
            // Inserting after each native row means the next native row now
            // naturally follows its preceding Korean reading.
            anchor.addView(
                reading,
                anchor.indexOfChild(precedingNativeCell) + 1,
                ViewGroup.LayoutParams(
                    AppleLineWidePronunciationRowPolicy.fullWidth(nativeMeasureKey.contentWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (visibleSoftRows != null) {
                reading.inlineWrapOverlay = softWrapOverlay
            }
            readingViews.add(reading)
        } }
        if (insertionResult.isFailure) {
            // A partially inserted reading must not leave an outer drawable
            // or modified native TextView spacing behind on an unknown host.
            softWrapOverlay?.clear()
            runCatching { clear(root) }
            AppleLyricUiRuntimeProbe.record(
                "linewide_bind_rollback",
                "error=${insertionResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}",
                root,
            )
            return false
        }
        val maxSourceLines = sourceWords.maxOfOrNull { it.layout?.lineCount ?: 0 } ?: 0
        if (visibleSoftRows != null) AppleLyricUiRuntimeProbe.record(
            "linewide_internal_wrap_interleaved",
            "sourceCells=${sourceChildren.size} visualRows=${visibleSoftRows.size} " +
                "readingChars=${text.length} nativeTimedWords=${payload.timedWords.size}",
            root,
        )
        if (rows == null && visibleSoftRows == null && maxSourceLines > 1) AppleLyricUiRuntimeProbe.record(
            "linewide_internal_wrap_fallback",
            "maxSourceLines=$maxSourceLines sourceCells=${sourceChildren.size} customPaint=true",
            root,
        )
        if (rows != null) AppleLyricUiRuntimeProbe.record(
            "linewide_visual_rows_interleaved",
            "sourceCells=${sourceChildren.size} visualRows=${rows.size} " +
                "readingChars=${text.length} nativeTimedWords=${payload.timedWords.size}",
            root,
        )
        return true
    }

    /** Used only as an ambiguity-checked fallback when native getters run before bind. */
    /** Cell boundaries are needed to distinguish the source from any translation script. */
    fun visibleNativeCellTexts(root: View?): List<String>? {
        val group = anchor(root) ?: return null
        return (0 until group.childCount).map(group::getChildAt)
            .filterNot { it is ReadingView }
            .map { child ->
                nativeWordTexts(child).joinToString("") { it.text.toString() }
            }
    }

    private fun anchor(root: View?): ViewGroup? {
        if (root !is ViewGroup || idName(root) != "karaoke_lyrics_line_container") return null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup && idName(child) == "karaoke_line_flexbox" &&
                child.javaClass.simpleName == "FullWidthAlphaGradientFlexboxLayout"
            ) return child
        }
        return null
    }

    private fun nativeWordTexts(root: View?): List<TextView> {
        val words = mutableListOf<TextView>()
        fun visit(view: View?, depth: Int) {
            if (view == null || view is ReadingView || depth > 8) return
            if (view is TextView && idName(view) == "song_lyrics_word") {
                words += view
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
        }
        visit(root, 0)
        return words
    }

    private fun idName(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
