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
        private var paintRoot: ViewGroup? = null
        private var outerForeground: AppleHangulReadingForeground? = null
        private val updatePaintLayer = Runnable { syncPaintLayer() }
        private var timedStyles: List<Pair<Int, KaraokeSyllableStyle>> = emptyList()
        private var timedVariants: List<List<AppleTimedReadingUnit>> = emptyList()
        private var timedVariantLookups: List<Map<Int, AppleTimedReadingUnit>> = emptyList()
        private val visibleBounds = Rect()
        private val highlightPulse = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                syncPaintLayer()
                val onScreen = isShown && getGlobalVisibleRect(visibleBounds)
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
            val onScreen = isAttachedToWindow && isShown &&
                getGlobalVisibleRect(visibleBounds)
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
            val styled = SpannableString(value)
            timedVariants = variants.map { units ->
                units.filter { it.start >= 0 && it.end <= value.length && it.start < it.end }
            }
            timedVariantLookups = timedVariants.map { it.associateBy(AppleTimedReadingUnit::start) }
            timedStyles = timedVariants.flatten().distinctBy(AppleTimedReadingUnit::start)
                .map { unit ->
                    val style = KaraokeSyllableStyle()
                    styled.setSpan(style, unit.start, unit.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    unit.start to style
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

        private fun detachOuterForeground() {
            outerForeground?.let { foreground ->
                paintRoot?.overlay?.remove(foreground)
            }
            outerForeground = null
            if (kotlin.math.abs(alpha - 1f) > 0.01f) alpha = 1f
        }

        private fun syncPaintLayer() {
            val root = paintRoot ?: return
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
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(highlightPulse)
            removeCallbacks(updatePaintLayer)
            detachOuterForeground()
            super.onDetachedFromWindow()
        }

        private fun syncTimedHighlight(
            positionMs: Long? = playbackPositionMs?.invoke(),
        ) {
            // The source line's view opacity also fades as Apple Music scrolls.
            // Share that line-level fade without copying any word's *single*
            // highlight color onto the entire Korean sentence.
            val parentAnchor = parent as? View
            val nativeStates = sourceWords.map { source ->
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
                val color = source.currentTextColor
                val brightness = (0.2126f * Color.red(color) +
                    0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
                brightness to opacity
            }
            val defaultNativeBrightness = nativeStates.filterNotNull()
                .maxOfOrNull { it.first * it.second } ?: 1f
            // The native flexbox measures this view but an outer holder
            // foreground paints its Layout outside the host's alpha mask.
            val activeVariantIndex = positionMs?.let {
                AppleTimedPronunciationHighlightPolicy.activeVariant(it, timedVariants)
            }
            val activeVariant = activeVariantIndex?.let(timedVariantLookups::get)
            var changed = false
            timedStyles.forEach { (offset, style) ->
                val nativeWord = activeVariant?.get(offset)
                val next = positionMs?.let { now ->
                    nativeWord?.let { unit ->
                        AppleTimedPronunciationHighlightPolicy.progress(now, unit)
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
                        ?.takeIf { it in nativeStates.indices }
                        ?.let(nativeStates::get)
                        ?.let { it.first * it.second } ?: defaultNativeBrightness
                if (kotlin.math.abs(style.nativeBrightness - nativeBrightness) > 0.01f) {
                    style.nativeBrightness = nativeBrightness
                    changed = true
                }
            }
            if (changed) {
                invalidate()
                outerForeground?.invalidateSelf()
            }
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

    fun bind(
        root: View?,
        payload: AppleLineWidePronunciationPayload?,
        currentSongId: String?,
        currentRevision: Long,
        visible: Boolean,
        currentSourcePriority: Int,
        trustedHolder: Boolean = false,
        playbackPositionMs: () -> Long? = { null },
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
        val childText = nativeChildren.map { child ->
            nativeWordTexts(child).joinToString("") { it.text.toString() }
        }
        val insertionIndex = (if (trustedHolder)
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                payload?.sourceText.orEmpty(), childText,
            ) else AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                payload?.sourceText.orEmpty(), childText,
        )) ?: run {
            clear(root)
            return false
        }
        val sourceChildren = nativeChildren.take(insertionIndex)
        val sourceWords = sourceChildren.flatMap { child ->
            nativeWordTexts(child)
        }
        val sourceStyle = sourceWords.firstOrNull() ?: run {
            clear(root)
            return false
        }
        val text = AppleLineWidePronunciationRowPolicy.readableText(payload?.pronunciation)
            ?: run {
                clear(root)
                return false
            }
        val existing = (0 until anchor.childCount)
            .map(anchor::getChildAt).filterIsInstance<ReadingView>()
        // Use the host's measured flex rows, never a guessed character count.
        // A native TextView that wraps internally cannot be safely divided
        // without replacing Apple's own word/shader renderer.
        val geometryPending = anchor.width <= 0 || sourceChildren.isEmpty() ||
            anchor.isLayoutRequested || root?.isLayoutRequested == true ||
            sourceChildren.any {
                it.isLayoutRequested || it.width <= 0 || it.height <= 0
            } || sourceWords.any { it.isLayoutRequested || it.layout == null }
        // A source TextView wrapping internally is NOT a transient layout
        // state. It cannot be split by moving flexbox child boundaries.
        val geometrySupported = sourceWords.all { it.layout?.lineCount == 1 }
        val geometryStable = !geometryPending && geometrySupported
        // During a pending holder remeasure, old coordinates are stale. Keep
        // the already-bound reading rows temporarily, rather than collapsing
        // them back to one block and triggering another unnecessary reflow.
        if (geometryPending && existing.isNotEmpty() && existing.all { reading ->
                reading.boundPayload == payload &&
                    reading.sourceCellAbove in sourceChildren &&
                    reading.sourceWords.size == sourceWords.size &&
                    sourceWords.indices.all { reading.sourceWords[it] === sourceWords[it] }
            }
        ) {
            existing.forEach { reading ->
                reading.playbackPositionMs = playbackPositionMs
                reading.bindPaintRoot(root)
                readingViews.add(reading)
            }
            return true
        }
        val rows = if (geometryStable) AppleInterleavedReadingPolicy.plan(
            sourceText = payload!!.sourceText,
            pronunciation = text,
            cells = sourceChildren.map { child ->
                AppleVisualSourceCell(
                    text = nativeWordTexts(child).joinToString("") { it.text.toString() },
                    top = child.top,
                    bottom = child.bottom,
                )
            },
            spans = payload.phoneticSpans,
        ) else null
        val desiredRows = rows ?: listOf(AppleInterleavedReadingRow(
            insertAfterSourceCell = insertionIndex - 1,
            text = text,
            readingStart = 0,
            readingEnd = text.length,
        ))
        val targetTextSize = AppleLineWidePronunciationRowPolicy.readingTextPx(sourceStyle.textSize)
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
                        AppleLineWidePronunciationRowPolicy.fullWidth(anchor.width) &&
                    kotlin.math.abs(view.textSize - targetTextSize) < 0.8f
            }
        if (canReuse) {
            existing.forEach { reading ->
                reading.playbackPositionMs = playbackPositionMs
                reading.bindPaintRoot(root)
                readingViews.add(reading)
            }
            return true
        }
        clear(root)
        val timedVariants = (listOf(payload!!.timedWords) + payload.repeatTimings)
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
        desiredRows.forEach { row ->
            val precedingNativeCell = sourceChildren[row.insertAfterSourceCell]
            val reading = ReadingView(anchor.context).apply {
                this.playbackPositionMs = playbackPositionMs
                this.sourceWords = sourceWords
                this.boundPayload = payload
                this.sourceCellAbove = precedingNativeCell
                this.readingStart = row.readingStart
                this.readingEnd = row.readingEnd
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
                    rows = desiredRows,
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
                    AppleLineWidePronunciationRowPolicy.fullWidth(anchor.width),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            readingViews.add(reading)
        }
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
