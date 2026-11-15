package io.github.proify.lyricon.amprovider.xposed

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
        val sourceText: String,
        val beginMs: Long,
        val endMs: Long,
        val translation: String,
        val revision: Long,
    )
    private val byHolder = WeakIdentityMap<Any, Context>()
    private val pulses = WeakHashMap<View, Pulse>()
    private val bindingGenerations = WeakHashMap<View, Long>()
    private val bySource = LinkedHashMap<String, Context>()
    private val ambiguousSources = HashSet<String>()
    private var indexedSongId: String? = null
    private var indexedRevision = -1L

    private fun sourceKey(text: String): String =
        AppleLineWidePronunciationRowPolicy.sourceKey(text)

    private fun rememberSource(ctx: Context) {
        synchronized(bySource) {
            if (indexedSongId != ctx.songId || indexedRevision != ctx.revision ||
                bySource.size > 512
            ) {
                bySource.clear()
                ambiguousSources.clear()
                indexedSongId = ctx.songId
                indexedRevision = ctx.revision
            }
            val key = sourceKey(ctx.sourceText)
            if (key.isBlank() || key in ambiguousSources) return
            val previous = bySource[key]
            // The same chorus may have multiple distinct timing windows:
            // don't highlight an unrelated recycled holder by guessing.
            if (previous != null && previous.beginMs != ctx.beginMs) {
                bySource.remove(key)
                ambiguousSources.add(key)
            } else {
                bySource[key] = ctx
            }
        }
    }

    fun remember(
        holder: Any?,
        songId: String?,
        sourceText: String?,
        beginMs: Long,
        endMs: Long,
        translation: String,
        revision: Long,
    ) {
        if (songId.isNullOrBlank() || sourceText.isNullOrBlank() ||
            beginMs < 0L || endMs <= beginMs || translation.isBlank()
        ) return
        val ctx = Context(songId, sourceText, beginMs, endMs, translation, revision)
        if (holder != null) byHolder[holder] = ctx
        rememberSource(ctx)
    }

    fun clearHolder(holder: Any?, root: View?) {
        holder?.let(byHolder::remove)
        root?.let { view ->
            pulses.remove(view)?.stop()
            bindingGenerations[view] = (bindingGenerations[view] ?: 0L) + 1L
        }
    }

    fun bind(
        holder: Any?,
        root: View?,
        currentSongId: String?,
        revision: Long,
        enabled: Boolean,
        playbackPositionMs: () -> Long?,
        observedSourceCells: List<String> = emptyList(),
        retry: Int = 0,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        root ?: return false
        if (!isCurrent()) return false
        if (retry == 0) pulses.remove(root)?.stop()
        val generation = bindingGenerations[root] ?: 0L
        fun retryAfterLayout(ctx: Context? = null) {
            if (retry >= 3 || !enabled) return
            if (ctx != null && holder != null) byHolder[holder] = ctx
            root.postDelayed({
                if (bindingGenerations[root] == generation &&
                    root.isAttachedToWindow && isCurrent()
                ) bind(
                    holder, root, currentSongId, revision, enabled,
                    playbackPositionMs, observedSourceCells, retry + 1, isCurrent,
                )
            }, when (retry) {
                0 -> 24L
                1 -> 96L
                else -> 220L
            })
        }
        val holderCtx = holder?.let { byHolder.remove(it) }
        val fallback = if (holderCtx == null && currentSongId != null &&
            observedSourceCells.isNotEmpty()
        ) synchronized(bySource) {
            if (indexedSongId != currentSongId || indexedRevision != revision) null
            else bySource.values.singleOrNull { candidate ->
                AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                    candidate.sourceText, observedSourceCells,
                ) != null
            }
        } else null
        val ctx = holderCtx ?: fallback
        if (ctx == null) {
            retryAfterLayout()
            return false
        }
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
        if (texts.isEmpty()) {
            retryAfterLayout(ctx)
            return false
        }

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
        if (translationView.isEmpty()) {
            retryAfterLayout(ctx)
            return false
        }
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
                if (active) {
                    originals.forEach { (view, _) ->
                        // Native Apple periodically animates text colors. A
                        // one-time white set is not enough: its next frame can
                        // restore the faded upper half of a wrapped translation.
                        if (view.currentTextColor != Color.WHITE) {
                            view.setTextColor(Color.WHITE)
                        }
                    }
                } else if (lastActive == true) {
                    originals.forEach { (view, colors) -> view.setTextColor(colors) }
                }
                lastActive = active
            }
            root.postDelayed(this, if (visible) 50L else 250L)
        }
    }
}
