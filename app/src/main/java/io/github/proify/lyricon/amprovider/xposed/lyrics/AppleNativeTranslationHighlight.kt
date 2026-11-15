package io.github.proify.lyricon.amprovider.xposed

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.util.WeakHashMap

/** Keep all wrapped Korean translation text on the SAME Apple source-line clock. */
internal object AppleNativeTranslationHighlightPolicy {
    fun lineActive(positionMs: Long?, beginMs: Long, endMs: Long): Boolean =
        positionMs != null && beginMs >= 0 && endMs > beginMs &&
            positionMs >= beginMs && positionMs < endMs

    fun compact(text: String): String =
        text.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    fun fragmentMatches(translation: String, candidate: String): Boolean {
        val expected = compact(translation)
        val actual = compact(candidate)
        // A one-letter candidate may be a Japanese source word or a rebinding
        // placeholder. Prefer actual translated fragments; never alter source.
        return actual.length >= 2 && expected.contains(actual)
    }
}

internal class AppleNativeTranslationHighlight {
    private data class Context(
        val songId: String,
        val beginMs: Long,
        val endMs: Long,
        val translation: String,
        val revision: Long,
    )
    private val byHolder = WeakIdentityMap<Any, Context>()
    private val pulses = WeakHashMap<View, Pulse>()

    fun remember(
        holder: Any?,
        songId: String?,
        beginMs: Long,
        endMs: Long,
        translation: String,
        revision: Long,
    ) {
        if (holder == null || songId.isNullOrBlank() ||
            beginMs < 0L || endMs <= beginMs || translation.isBlank()
        ) return
        byHolder[holder] = Context(songId, beginMs, endMs, translation, revision)
    }

    fun clearHolder(holder: Any?, root: View?) {
        holder?.let(byHolder::remove)
        root?.let { pulses.remove(it)?.stop() }
    }

    fun bind(
        holder: Any?,
        root: View?,
        currentSongId: String?,
        revision: Long,
        enabled: Boolean,
        playbackPositionMs: () -> Long?,
    ): Boolean {
        root ?: return false
        pulses.remove(root)?.stop()
        val ctx = holder?.let { byHolder.remove(it) } ?: return false
        if (!enabled || ctx.songId != currentSongId || ctx.revision != revision) return false
        val texts = mutableListOf<TextView>()
        fun scan(view: View, depth: Int) {
            if (depth > 12) return
            if (view is TextView) {
                if (AppleNativeTranslationHighlightPolicy.fragmentMatches(
                        ctx.translation, view.text.toString()
                    )
                ) texts.add(view)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
            }
        }
        scan(root, 0)
        if (texts.isEmpty()) return false

        // Exclude Hangul source-reading cells even when a short phonetic token
        // accidentally matches part of a translated word.
        val translationView = texts.filter { view ->
            var parent: View? = view
            var readingContainer = false
            while (parent != null && parent !== root) {
                val id = runCatching {
                    parent.resources.getResourceEntryName(parent.id)
                }.getOrNull().orEmpty()
                if (id.contains("pronunciation", ignoreCase = true)) {
                    readingContainer = true
                    break
                }
                parent = parent.parent as? View
            }
            !readingContainer
        }
        if (translationView.isEmpty()) return false
        val pulse = Pulse(root, ctx, translationView, playbackPositionMs)
        pulses[root] = pulse
        pulse.start()
        return true
    }

    private class Pulse(
        private val root: View,
        private val ctx: Context,
        views: List<TextView>,
        private val playbackPositionMs: () -> Long?,
    ) : Runnable, View.OnAttachStateChangeListener {
        private val originals = views.map { it to it.textColors }
        private var running = false
        private var lastActive: Boolean? = null
        private val bounds = Rect()

        fun start() {
            if (running) return
            running = true
            root.addOnAttachStateChangeListener(this)
            root.post(this)
        }

        fun stop() {
            if (!running) return
            running = false
            root.removeOnAttachStateChangeListener(this)
            root.removeCallbacks(this)
            originals.forEach { (text, colors) -> text.setTextColor(colors) }
        }

        override fun onViewAttachedToWindow(v: View) {
            if (running) root.post(this)
        }

        override fun onViewDetachedFromWindow(v: View) {
            // A recycled row must not leave stale translated colors.
            stop()
        }

        override fun run() {
            if (!running) return
            if (!root.isAttachedToWindow) return
            val visible = root.isShown && root.getGlobalVisibleRect(bounds)
            if (visible) {
                val active = AppleNativeTranslationHighlightPolicy.lineActive(
                    playbackPositionMs(), ctx.beginMs, ctx.endMs,
                )
                if (active != lastActive) {
                    originals.forEach { (view, colors) ->
                        // In native Apple layout each wrapped TextView can
                        // otherwise retain a different active/color phase.
                        view.setTextColor(if (active)
                            ColorStateList.valueOf(Color.WHITE) else colors)
                    }
                    lastActive = active
                }
            }
            root.postDelayed(this, if (visible) 50L else 250L)
        }
    }
}
