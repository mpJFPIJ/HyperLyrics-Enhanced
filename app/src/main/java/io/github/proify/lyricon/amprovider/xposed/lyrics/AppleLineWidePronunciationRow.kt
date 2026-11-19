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
    fun nextDelayMs(
        onScreen: Boolean,
        positionMs: Long?,
        nextBeginMs: Long?,
        active: Boolean,
    ): Long = if (onScreen && (active ||
            (positionMs != null && nextBeginMs != null &&
                nextBeginMs - positionMs in 0L..450L))) 60L else 400L
}

/**
 * Attached to Apple's measured karaoke_line_flexbox, not to any timed native word.
 * The flexbox wraps this full-width child below the intact native Japanese line.
 * Only observed Apple Music 6.5.3 view anchors are accepted; unfamiliar hosts fail closed.
 */
internal class AppleLineWidePronunciationRow {
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
        private var paintRoot: ViewGroup? = null
        private var outerForeground: AppleHangulReadingForeground? = null
        private val updatePaintLayer = Runnable { syncPaintLayer() }
        private var timedStyles: List<Pair<Int, KaraokeSyllableStyle>> = emptyList()
        private var timedVariants: List<List<AppleTimedReadingUnit>> = emptyList()
        private var timedVariantLookups: List<Map<Int, AppleTimedReadingUnit>> = emptyList()
        private var variantBeginTimes = longArrayOf()
        private val visibleBounds = Rect()
        private val highlightPulse = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                syncPaintLayer()
                val onScreen = isShown && getGlobalVisibleRect(visibleBounds)
                if (onScreen) syncTimedHighlight()
                // Apple's karaoke line may animate from its own RenderNode.
                // Refresh the per-syllable paint from the actual playback clock.
                // Never poll unrelated/off-screen lines at animation rate.
                // Native word cells own fast karaoke animation; this fallback
                // is only a last-resort line-wide display.
                val position = playbackPositionMs?.invoke()
                postDelayed(this, AppleReadingPulsePolicy.nextDelayMs(
                    onScreen = onScreen,
                    positionMs = position,
                    nextBeginMs = position?.let { now ->
                        variantBeginTimes.firstOrNull { begin -> begin >= now }
                    },
                    active = position != null &&
                        AppleTimedPronunciationHighlightPolicy.activeVariant(
                            position, timedVariants,
                        ) != null,
                ))
            }
        }

        fun setTimedText(value: String, variants: List<List<AppleTimedReadingUnit>>) {
            val styled = SpannableString(value)
            timedVariants = variants.map { units ->
                units.filter { it.start >= 0 && it.end <= value.length && it.start < it.end }
            }
            variantBeginTimes = timedVariants.mapNotNull { units ->
                units.minOfOrNull(AppleTimedReadingUnit::beginMs)
                    ?.takeIf { it >= 0L }
            }.sorted().toLongArray()
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
            val measured = isAttachedToWindow && root.isAttachedToWindow &&
                width > 0 && height > 0 && root.width > 0 && root.height > 0
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
            if (newForeground) outerForeground?.invalidateSelf()
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

        private fun syncTimedHighlight() {
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
            val position = playbackPositionMs?.invoke()
            val activeVariantIndex = position?.let {
                AppleTimedPronunciationHighlightPolicy.activeVariant(it, timedVariants)
            }
            val activeVariant = activeVariantIndex?.let(timedVariantLookups::get)
            var changed = false
            timedStyles.forEach { (offset, style) ->
                val nativeWord = activeVariant?.get(offset)
                val next = position?.let { now ->
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
        for (view in views) {
            if (!isUnder(view, "pronunciation") || isUnder(view, "translation") ||
                !view.text.any { it in '가'..'힣' }
            ) continue
            if (kotlin.math.abs(view.textSize - targetSize) > 0.8f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetSize)
                adjusted++
            }
        }
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
            if (anchor.getChildAt(index) is ReadingView) {
                anchor.removeViewAt(index)
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
    ): Boolean {
        val anchor = anchor(root) ?: return false
        if (!AppleLineWidePronunciationRowPolicy.shouldDisplay(
                payload, currentSongId, currentRevision, visible, currentSourcePriority,
        )) {
            clear(root)
            return false
        }

        val childText = visibleNativeCellTexts(root).orEmpty()
        val insertionIndex = (if (trustedHolder)
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                payload?.sourceText.orEmpty(), childText,
            ) else AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                payload?.sourceText.orEmpty(), childText,
        )) ?: run {
            clear(root)
            return false
        }
        val sourceWords = (0 until insertionIndex).flatMap { index ->
            nativeWordTexts(anchor.getChildAt(index))
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
            .map(anchor::getChildAt).filterIsInstance<ReadingView>().singleOrNull()
        if (existing != null && AppleLineWidePronunciationRowPolicy.canReuseReading(
                boundPayload = existing.boundPayload,
                requestedPayload = payload!!,
                oldWords = existing.sourceWords,
                newWords = sourceWords,
                currentIndex = anchor.indexOfChild(existing),
                insertionIndex = insertionIndex,
            ) && kotlin.math.abs(existing.textSize -
                AppleLineWidePronunciationRowPolicy.readingTextPx(sourceStyle.textSize)) < 0.8f
        ) {
            existing.playbackPositionMs = playbackPositionMs
            existing.bindPaintRoot(root)
            return true
        }
        clear(root)
        val reading = ReadingView(anchor.context).apply {
            this.playbackPositionMs = playbackPositionMs
            this.sourceWords = sourceWords
            this.boundPayload = payload
            bindPaintRoot(root)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                AppleLineWidePronunciationRowPolicy.readingTextPx(sourceStyle.textSize),
            )
            // Any syllable with no verified native timing stays visibly dim.
            setTextColor(Color.rgb(110, 110, 110))
            typeface = sourceStyle?.typeface
            gravity = Gravity.START
            // Leave ascender/descender space when the native flexbox measures
            // this hidden layout-only copy and the outer holder repaints it.
            includeFontPadding = true
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
            }
            setTimedText(
                text,
                (listOf(payload!!.timedWords) + payload.repeatTimings).map { native ->
                    AppleTimedPronunciationHighlightPolicy.units(
                        sourceText = payload.sourceText,
                        pronunciation = text,
                        timedWords = native,
                        phoneticSpans = payload.phoneticSpans,
                        lineBeginMs = payload.lineBeginMs,
                        lineEndMs = payload.lineEndMs,
                    )
                },
            )
        }
        // MATCH_PARENT takes the whole karaoke flexbox width, unlike native
        // lyrics_pronunciation_vertical_container cells (~206–373px in the device log).
        anchor.addView(
            reading,
            insertionIndex,
            ViewGroup.LayoutParams(
                AppleLineWidePronunciationRowPolicy.fullWidth(anchor.width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return true
    }

    /** Used only as an ambiguity-checked fallback when native getters run before bind. */
    /** Cell boundaries are needed to distinguish the source from any translation script. */
    fun visibleNativeCellTexts(root: View?): List<String>? {
        val group = anchor(root) ?: return null
        return (0 until group.childCount).map { index ->
            nativeWordTexts(group.getChildAt(index)).joinToString("") { it.text.toString() }
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
