package io.github.proify.lyricon.amprovider.xposed

import android.os.Build
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
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
)

internal object AppleLineWidePronunciationRowPolicy {
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

    /** Negative width uses MATCH_PARENT until the flexbox has been laid out. */
    fun fullWidth(measuredWidth: Int): Int = measuredWidth.takeIf { it > 0 } ?: -1
}

/**
 * Attached to Apple's measured karaoke_line_flexbox, not to any timed native word.
 * The flexbox wraps this full-width child below the intact native Japanese line.
 * Only observed Apple Music 6.5.3 view anchors are accepted; unfamiliar hosts fail closed.
 */
internal class AppleLineWidePronunciationRow {
    // This view is injected into Apple Music's classloader and must depend only
    // on platform Android widgets; requiring AppCompat could break NPatch Local.
    @SuppressLint("AppCompatCustomView")
    private class ReadingView(context: android.content.Context) : TextView(context) {
        var sourceWords: List<TextView> = emptyList()
        private val visibleBounds = Rect()
        private val highlightPulse = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                val onScreen = isShown && getGlobalVisibleRect(visibleBounds)
                if (onScreen) syncNativeHighlight()
                // A sibling's RenderNode may animate without invalidating this
                // TextView. Poll only while attached, at a slower idle cadence
                // for lines that have scrolled offscreen.
                postDelayed(this, if (onScreen) 33L else 250L)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(highlightPulse)
            post(highlightPulse)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(highlightPulse)
            super.onDetachedFromWindow()
        }

        /** Apple's own word views animate their text color and parent alpha. */
        private fun syncNativeHighlight() {
            val parentAnchor = parent as? View ?: return
            val brightest = sourceWords.mapNotNull { source ->
                if (!source.isAttachedToWindow) return@mapNotNull null
                var opacity = 1f
                var view: View? = source
                while (view != null && view !== parentAnchor) {
                    opacity *= view.alpha
                    view = view.parent as? View
                }
                if (view !== parentAnchor) return@mapNotNull null
                val color = source.currentTextColor
                val luminance = 0.2126f * Color.red(color) +
                    0.7152f * Color.green(color) + 0.0722f * Color.blue(color)
                Triple(color, opacity, luminance * Color.alpha(color) / 255f * opacity)
            }.maxByOrNull { it.third } ?: return
            if (currentTextColor != brightest.first) setTextColor(brightest.first)
            if (kotlin.math.abs(alpha - brightest.second) > 0.01f) alpha = brightest.second
        }

        override fun onDraw(canvas: Canvas) {
            syncNativeHighlight()
            super.onDraw(canvas)
        }
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
    ): Boolean {
        val anchor = anchor(root) ?: return false
        clear(root)
        if (!AppleLineWidePronunciationRowPolicy.shouldDisplay(
                payload, currentSongId, currentRevision, visible, currentSourcePriority,
            )
        ) return false

        val childText = visibleNativeCellTexts(root).orEmpty()
        val insertionIndex = AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
            payload?.sourceText.orEmpty(), childText,
        ) ?: return false
        val sourceWords = (0 until insertionIndex).flatMap { index ->
            nativeWordTexts(anchor.getChildAt(index))
        }
        val sourceStyle = sourceWords.firstOrNull() ?: return false
        val text = AppleLineWidePronunciationRowPolicy.readableText(payload?.pronunciation)
            ?: return false
        val reading = ReadingView(anchor.context).apply {
            this.sourceWords = sourceWords
            setText(text)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                sourceStyle?.textSize?.times(0.49f)
                    ?: (16f * resources.displayMetrics.scaledDensity),
            )
            setTextColor(sourceStyle?.currentTextColor ?: Color.WHITE)
            typeface = sourceStyle?.typeface
            gravity = Gravity.START
            includeFontPadding = false
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
