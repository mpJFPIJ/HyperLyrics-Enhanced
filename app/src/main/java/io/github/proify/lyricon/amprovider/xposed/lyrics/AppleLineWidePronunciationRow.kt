package io.github.proify.lyricon.amprovider.xposed

import android.os.Build
import android.graphics.Color
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
    fun sourceKey(text: String): String = text.filterNot(Char::isWhitespace)

    fun readableText(value: String?): String? = value?.trim()
        ?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotEmpty)

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
    private class ReadingView(context: android.content.Context) : TextView(context)

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

        val sourceStyle = findNativeWordText(anchor)
        val text = AppleLineWidePronunciationRowPolicy.readableText(payload?.pronunciation)
            ?: return false
        val reading = ReadingView(anchor.context).apply {
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
            ViewGroup.LayoutParams(
                AppleLineWidePronunciationRowPolicy.fullWidth(anchor.width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return true
    }

    /** Used only as an ambiguity-checked fallback when native getters run before bind. */
    fun visibleOriginalSource(root: View?): String? {
        val group = anchor(root) ?: return null
        val text = StringBuilder()
        fun collect(view: View, depth: Int) {
            if (depth > 8 || view is ReadingView) return
            if (view is TextView && idName(view) == "song_lyrics_word") {
                text.append(view.text)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let { collect(it, depth + 1) }
                }
            }
        }
        collect(group, 0)
        return text.toString().takeIf(String::isNotBlank)
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

    private fun findNativeWordText(group: ViewGroup): TextView? {
        fun find(view: View, depth: Int): TextView? {
            if (depth > 8) return null
            if (view is TextView && idName(view) == "song_lyrics_word") return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    find(view.getChildAt(index), depth + 1)?.let { return it }
                }
            }
            return null
        }
        return find(group, 0)
    }

    private fun idName(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
