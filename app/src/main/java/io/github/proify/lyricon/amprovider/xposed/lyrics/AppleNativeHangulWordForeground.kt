package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Eligibility is structural, independent of the currently selected karaoke word.
 * Only the existing native Hangul TextViews own their word colors and timings. */
internal object AppleNativeHangulWordPaintPolicy {
    fun eligible(
        hangul: Boolean,
        underPronunciation: Boolean,
        underTranslation: Boolean,
        injectedReading: Boolean,
        measured: Boolean,
    ): Boolean = hangul && underPronunciation && !underTranslation &&
        !injectedReading && measured

    fun opacity(childAlpha: Float, ancestorAlphas: List<Float>): Float =
        ancestorAlphas.fold(childAlpha) { opacity, parent -> opacity * parent }
            .coerceIn(0f, 1f)

    /** The native parent gradient must keep muting every *other* lyric row. */
    fun focusedRow(playbackAdapterPosition: Int?, holderAdapterPosition: Int?): Boolean =
        playbackAdapterPosition != null && playbackAdapterPosition == holderAdapterPosition

    fun nextRefreshMs(visible: Boolean, focused: Boolean = true): Long = when {
        !visible -> 600L
        focused -> 80L
        else -> 180L
    }
}

/** A second *paint pass*, not a second TextView. This can bypass the native
 * flexbox gradient while retaining each native cell's current Spanned Layout,
 * size, position, TextPaint shader and word-specific alpha. Native cells stay
 * visible underneath as a safe fallback until this painter is measurable. */
internal class AppleNativeHangulWordForeground(
    private val holderRoot: ViewGroup,
    val wordCells: List<TextView>,
) : Drawable() {
    val boundText = wordCells.map { it.text.toString() }
    private val local = Rect()

    fun intact(): Boolean = wordCells.size == boundText.size &&
        wordCells.indices.all { index -> wordCells[index].text.toString() == boundText[index] }

    fun syncBounds() {
        if (holderRoot.width > 0 && holderRoot.height > 0 &&
            (bounds.width() != holderRoot.width || bounds.height() != holderRoot.height)
        ) setBounds(0, 0, holderRoot.width, holderRoot.height)
    }

    override fun draw(canvas: Canvas) {
        if (!holderRoot.isAttachedToWindow || !intact()) return
        val globalSave = canvas.save()
        try {
            // The outer lyric holder is the only clip. Native word containers
            // are allowed to measure tightly without cropping Hangul overhang.
            canvas.clipRect(0, 0, holderRoot.width, holderRoot.height)
            for (word in wordCells) {
                if (!word.isShown || word.width <= 0 || word.height <= 0) continue
                val layout = word.layout ?: continue
                local.set(0, 0, word.width, word.height)
                if (runCatching {
                        holderRoot.offsetDescendantRectToMyCoords(word, local)
                    }.isFailure
                ) continue
                val parentAlphas = mutableListOf<Float>()
                var parent = word.parent as? View
                while (parent != null && parent !== holderRoot) {
                    parentAlphas += parent.alpha
                    parent = parent.parent as? View
                }
                if (parent !== holderRoot) continue
                val opacity = AppleNativeHangulWordPaintPolicy.opacity(word.alpha, parentAlphas)
                if (opacity <= 0.005f) continue
                val save = canvas.save()
                try {
                    canvas.translate(
                        (local.left + word.compoundPaddingLeft - word.scrollX).toFloat(),
                        (local.top + word.totalPaddingTop - word.scrollY).toFloat(),
                    )
                    val paint = layout.paint
                    val originalAlpha = paint.alpha
                    val originalColor = paint.color
                    val originalShader = paint.shader
                    try {
                        // Touch alpha only. The host's native CharacterStyles
                        // still choose foreground colors and per-word progress.
                        paint.alpha = (originalAlpha * opacity).roundToInt().coerceIn(0, 255)
                        layout.draw(canvas)
                    } finally {
                        paint.alpha = originalAlpha
                        paint.color = originalColor
                        paint.shader = originalShader
                    }
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        } finally {
            canvas.restoreToCount(globalSave)
        }
    }

    override fun setAlpha(alpha: Int) { /* Host row alpha remains the owner. */ }
    override fun setColorFilter(colorFilter: ColorFilter?) { /* Keep native shader. */ }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A bound holder owns one native overlay. Weak values prevent detached or
 * prefetched native views from being retained solely by this manager. */
internal class AppleNativeHangulWordOverlayManager {
    private val byRoot = WeakHashMap<ViewGroup, WeakReference<Session>>()

    fun bind(root: View?, cells: List<TextView>, focused: () -> Boolean = { false }) {
        val holder = root as? ViewGroup ?: return
        val existing = byRoot[holder]?.get()
        if (existing?.matches(cells) == true) {
            existing.setFocus(focused)
            existing.refresh()
            return
        }
        existing?.stop()
        byRoot.remove(holder)
        if (cells.isEmpty()) return
        val session = Session(holder, cells, focused) { old ->
            if (byRoot[holder]?.get() === old) byRoot.remove(holder)
        }
        byRoot[holder] = WeakReference(session)
        session.start()
    }

    private class Session(
        private val root: ViewGroup,
        cells: List<TextView>,
        private var isFocused: () -> Boolean,
        private val onStop: (Session) -> Unit,
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private val painter = AppleNativeHangulWordForeground(root, cells)
        private val visibleBounds = Rect()
        private val refresh = object : Runnable {
            override fun run() {
                if (!running || !root.isAttachedToWindow) return
                if (!painter.intact()) {
                    stop()
                    onStop(this@Session)
                    return
                }
                val focused = isFocused()
                val measured = root.width > 0 && root.height > 0 &&
                    painter.wordCells.any { word ->
                        AppleNativeHangulWordPaintPolicy.eligible(
                            hangul = word.text.any { it in '가'..'힣' },
                            underPronunciation = true, underTranslation = false,
                            injectedReading = false,
                            measured = word.isAttachedToWindow && word.layout != null &&
                                word.width > 0 && word.height > 0,
                        )
                    }
                if (focused && measured && !installed) {
                    painter.syncBounds()
                    if (runCatching { root.overlay.add(painter) }.isSuccess) {
                        installed = true
                        AppleLyricUiRuntimeProbe.record(
                            "native_reading_overlay_installed",
                            "words=${painter.wordCells.size} width=${root.width} height=${root.height}",
                            root,
                        )
                    }
                }
                if ((!focused || !measured) && installed) {
                    root.overlay.remove(painter)
                    installed = false
                }
                val visible = root.isShown && root.getGlobalVisibleRect(visibleBounds)
                if (visible && installed) {
                    painter.syncBounds()
                    painter.invalidateSelf()
                }
                root.postDelayed(this, AppleNativeHangulWordPaintPolicy.nextRefreshMs(
                    visible = visible, focused = focused,
                ))
            }
        }
        private var running = false
        private var installed = false

        fun matches(candidates: List<TextView>): Boolean = running && painter.intact() &&
            candidates.size == painter.wordCells.size &&
            candidates.indices.all { painter.wordCells[it] === candidates[it] }

        fun setFocus(focused: () -> Boolean) { isFocused = focused }

        fun refresh() {
            if (!running || !root.isAttachedToWindow) return
            root.removeCallbacks(refresh)
            root.post(refresh)
        }

        fun start() {
            running = true
            root.addOnAttachStateChangeListener(this)
            root.addOnLayoutChangeListener(this)
            refresh()
        }

        fun stop() {
            if (!running) return
            running = false
            suspendPaint()
            root.removeOnAttachStateChangeListener(this)
            root.removeOnLayoutChangeListener(this)
        }

        private fun suspendPaint() {
            root.removeCallbacks(refresh)
            if (installed) root.overlay.remove(painter)
            installed = false
        }

        override fun onViewAttachedToWindow(v: View) = refresh()
        override fun onViewDetachedFromWindow(v: View) {
            // RecyclerView holders may detach temporarily during scrolling
            // and reattach without a new onBind. Keep the listener, but never
            // retain an overlay or a timer while detached.
            suspendPaint()
        }
        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) {
            // Scrolling moves the holder often without changing its measured
            // size. The overlay moves with it; don't reschedule each scroll frame.
            if (right - left != oldRight - oldLeft ||
                bottom - top != oldBottom - oldTop
            ) refresh()
        }
    }
}
