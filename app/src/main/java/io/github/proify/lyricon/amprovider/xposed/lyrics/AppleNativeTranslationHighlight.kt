package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Color
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

internal data class AppleTranslationPaintOwners(
    val nativeVisible: Boolean,
    val foregroundVisible: Boolean,
)

/** Keep all wrapped Korean translation text on the SAME Apple source-line clock. */
internal object AppleNativeTranslationHighlightPolicy {
    /** A translated line must never be painted by both Apple and our overlay. */
    fun paintOwners(lineActive: Boolean, foregroundReady: Boolean): AppleTranslationPaintOwners {
        val useForeground = lineActive && foregroundReady
        return AppleTranslationPaintOwners(
            nativeVisible = !useForeground,
            foregroundVisible = useForeground,
        )
    }

    /** Never restore the old lyric into a TextView already rebound to another row. */
    fun isStillBound(controlledText: CharSequence, currentText: CharSequence): Boolean =
        controlledText === currentText

    fun lineActive(positionMs: Long?, beginMs: Long, endMs: Long): Boolean =
        positionMs != null && beginMs >= 0 && endMs > beginMs &&
            positionMs >= beginMs && positionMs < endMs

    fun compact(text: String): String =
        text.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    /**
     * The original implementation scanned every TextView in the entire native
     * row, then recolored arbitrary matching fragments. That could affect a
     * reading or a future source lyric. Only the 6.5.3 translation TextView is
     * safe to modify; accept an exact-text match when its ID is unavailable.
     */
    fun shouldRenderTranslationCell(
        viewId: String?,
        translation: String,
        candidate: String,
    ): Boolean {
        val normalizedId = viewId.orEmpty()
        if (normalizedId == "lyrics_translation_line")
            return fragmentMatches(translation, candidate)
        return normalizedId.isEmpty() &&
            compact(candidate).length >= 2 &&
            compact(candidate) == compact(translation)
    }

    fun fragmentMatches(translation: String, candidate: String): Boolean {
        val expected = compact(translation)
        val actual = compact(candidate)
        // A one-letter candidate may be a Japanese source word or a rebinding
        // placeholder. Prefer actual translated fragments; never alter source.
        return actual.length >= 2 && expected.contains(actual)
    }
}

internal class AppleNativeTranslationHighlight {
    private val byHolder = AppleTranslationLineContextStore()
    private val pulses = WeakHashMap<View, Pulse>()
    private val bindingGenerations = WeakHashMap<View, Long>()
    private val bySource = LinkedHashMap<String, AppleTranslationLineContext>()
    private val ambiguousSources = HashSet<String>()
    private var indexedSongId: String? = null
    private var indexedRevision = -1L

    private fun sourceKey(text: String): String =
        AppleLineWidePronunciationRowPolicy.sourceKey(text)

    private fun rememberSource(ctx: AppleTranslationLineContext) {
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
        val ctx = AppleTranslationLineContext(
            songId, sourceText, beginMs, endMs, translation, revision,
        )
        byHolder.remember(holder, ctx)
        rememberSource(ctx)
    }

    fun clearHolder(holder: Any?, root: View?) {
        byHolder.clear(holder)
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
        fun retryAfterLayout(ctx: AppleTranslationLineContext? = null) {
            if (retry >= 3 || !enabled) return
            if (ctx != null) byHolder.remember(holder, ctx)
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
        // A holder may be bound repeatedly while its lyric line is visible;
        // consuming this context on the first pass made subsequent calls lose
        // their source clock and log translation_context_missing.
        val holderCtx = byHolder.forHolder(holder)
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
            AppleLyricUiRuntimeProbe.record(
                "translation_context_missing",
                "holderPresent=${if (holder != null) 1 else 0} " +
                    "sourceCellCount=${observedSourceCells.size} retry=$retry",
                root,
            )
            retryAfterLayout()
            return false
        }
        if (!enabled || ctx.songId != currentSongId || ctx.revision != revision) return false
        val texts = mutableListOf<TextView>()
        fun scan(view: View, depth: Int) {
            if (depth > 12) return
            if (view is TextView) {
                // The injected Hangul ReadingView is not a Korean meaning
                // line, even if its text happens to occur in the translation.
                val entry = runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrNull()
                if (view.javaClass.simpleName != "ReadingView" &&
                    AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
                        entry, ctx.translation, view.text.toString(),
                    )
                ) texts.add(view)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
            }
        }
        scan(root, 0)
        if (texts.isEmpty()) {
            AppleLyricUiRuntimeProbe.record(
                "translation_cell_missing",
                "candidateTextLen=${ctx.translation.length} retry=$retry",
                root,
            )
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
        AppleLyricUiRuntimeProbe.record(
            "translation_cells_bound",
            "matchedCells=${translationView.size} " +
                "lineDurationMs=${ctx.endMs - ctx.beginMs} retry=$retry",
            root,
        )
        val pulse = Pulse(
            root, ctx, translationView, playbackPositionMs,
            onDetach = { old, detached ->
                if (pulses[detached] === old) pulses.remove(detached)
            },
        )
        pulses[root] = pulse
        pulse.start()
        return true
    }

    private class Pulse(
        private val root: View,
        private val ctx: AppleTranslationLineContext,
        views: List<TextView>,
        private val playbackPositionMs: () -> Long?,
        private val onDetach: (Pulse, View) -> Unit,
    ) : Runnable, View.OnAttachStateChangeListener {
        private data class SavedStyle(
            val view: TextView,
            val colors: android.content.res.ColorStateList,
            val opacity: Float,
            val originalText: CharSequence,
            var controlledText: CharSequence,
            var hiddenByPulse: Boolean = false,
        )
        private val originals = views.map {
            SavedStyle(it, it.textColors, it.alpha, it.text, it.text)
        }
        private val inactiveColor = Color.rgb(145, 145, 145)

        /**
         * Apple draws Korean meaning through a colored Spanned CharSequence.
         * TextView.setTextColor() cannot override spans, so the upper half of
         * wrapped lyrics kept the native gradient even when the line was
         * active. Strip only color spans once on bind; preserve typeface,
         * layout, size and all non-color spans.
         */
        private fun removeConflictingColorSpans(view: TextView) {
            val source = view.text as? Spanned ?: return
            val copy = SpannableStringBuilder(source)
            val conflicting = copy.getSpans(
                0, copy.length, CharacterStyle::class.java,
            ).filter { span ->
                span is ForegroundColorSpan || span is BackgroundColorSpan ||
                    span.javaClass.simpleName.contains("gradient", ignoreCase = true) ||
                    span.javaClass.simpleName.contains("karaoke", ignoreCase = true)
            }
            if (conflicting.isEmpty()) return
            conflicting.forEach(copy::removeSpan)
            view.setText(copy, TextView.BufferType.SPANNABLE)
        }
        private var running = false
        private var lastActive: Boolean? = null
        private var lastForegroundReady: Boolean? = null
        private val bounds = Rect()
        private var aboveNativeGradient: AppleKoreanTranslationForeground? = null

        private fun attachForeground(): Boolean {
            // If any wrapped cell has no measurable layout yet, keep Apple's
            // original meaning fully visible until the overlay can draw all
            // cells. Never hide the native fallback on a partial bind.
            if (root !is ViewGroup || root.width <= 0 || root.height <= 0 || originals.any { saved ->
                    saved.view.layout == null ||
                        saved.view.width <= 0 || saved.view.height <= 0
                }
            ) {
                detachForeground()
                return false
            }
            if (aboveNativeGradient == null) {
                // FullWidthAlphaGradientFlexboxLayout masks the child even
                // after TextView.setTextColor(WHITE). A ViewOverlay on the
                // *outer lyric holder* draws this one Korean meaning on top
                // of that nested native gradient without changing Japanese
                // source words, the pronunciation vector or their clocks.
                val drawable = AppleKoreanTranslationForeground(
                    root, originals.map { it.view }, Color.WHITE,
                )
                drawable.setBounds(0, 0, root.width, root.height)
                if (runCatching { root.overlay.add(drawable) }.isFailure) return false
                aboveNativeGradient = drawable
            }
            aboveNativeGradient?.let { drawable ->
                drawable.setBounds(0, 0, root.width, root.height)
                drawable.invalidateSelf()
            }
            return aboveNativeGradient != null
        }

        private fun detachForeground() {
            aboveNativeGradient?.let(root.overlay::remove)
            aboveNativeGradient = null
        }

        fun start() {
            if (running) return
            running = true
            root.addOnAttachStateChangeListener(this)
            originals.forEach { saved ->
                removeConflictingColorSpans(saved.view)
                saved.controlledText = saved.view.text
            }
            root.post(this)
        }

        fun stop() {
            if (!running) return
            running = false
            root.removeOnAttachStateChangeListener(this)
            root.removeCallbacks(this)
            detachForeground()
            originals.forEach { saved ->
                if (!AppleNativeTranslationHighlightPolicy.isStillBound(
                        saved.controlledText, saved.view.text,
                    )) {
                    // RecyclerView may replace the text before this pulse is
                    // stopped. Never restore its old text/colors, but do undo
                    // our zero-alpha override so the next lyric isn't hidden.
                    if (saved.hiddenByPulse && saved.view.alpha <= 0.01f)
                        saved.view.alpha = 1f
                    return@forEach
                }
                saved.view.setText(saved.originalText)
                saved.view.setTextColor(saved.colors)
                saved.view.alpha = saved.opacity
            }
        }

        override fun onViewAttachedToWindow(v: View) {
            if (running) root.post(this)
        }

        override fun onViewDetachedFromWindow(v: View) {
            // Detach must remove the map entry: a WeakHashMap with a value
            // pointing back to its View would otherwise retain the entire
            // native karaoke row, its translation and attached TextViews.
            stop()
            onDetach(this, v)
        }

        override fun run() {
            if (!running) return
            if (!root.isAttachedToWindow) return
            if (originals.any { saved ->
                    !AppleNativeTranslationHighlightPolicy.isStillBound(
                        saved.controlledText, saved.view.text,
                    )
                }
            ) {
                // Apple's RecyclerView reused these TextViews for a new lyric.
                // An old pulse must never recolor or restore that new content.
                stop()
                onDetach(this, root)
                return
            }
            val visible = root.isShown && root.getGlobalVisibleRect(bounds)
            if (visible) {
                val active = AppleNativeTranslationHighlightPolicy.lineActive(
                    playbackPositionMs(), ctx.beginMs, ctx.endMs,
                )
                val foregroundReady = if (active) attachForeground() else {
                    detachForeground()
                    false
                }
                val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
                    lineActive = active, foregroundReady = foregroundReady,
                )
                if (active) {
                    originals.filter { saved ->
                        AppleNativeTranslationHighlightPolicy.isStillBound(
                            saved.controlledText, saved.view.text,
                        )
                    }.forEach { saved ->
                        val view = saved.view
                        // Apple's native animation can update both the word
                        // color and TextView alpha while the line is active.
                        // Our outer overlay is the sole visible painter when
                        // ready. Alpha zero hides Apple's gradient-masked
                        // duplicate without removing its layout or timing.
                        if (view.currentTextColor != Color.WHITE) {
                            view.setTextColor(Color.WHITE)
                        }
                        val nativeAlpha = if (owners.nativeVisible) 1f else 0f
                        if (kotlin.math.abs(view.alpha - nativeAlpha) > 0.01f) {
                            view.alpha = nativeAlpha
                        }
                        saved.hiddenByPulse = !owners.nativeVisible
                    }
                } else {
                    detachForeground()
                    // A wrapped translation is ONE logical lyric sentence.
                    // Apple's old word animation can leave a bright bottom
                    // fragment and dim top fragment even after the line ends.
                    // Keep the entire inactive translation in one neutral
                    // state instead of restoring its per-fragment mismatch.
                    originals.filter { saved ->
                        AppleNativeTranslationHighlightPolicy.isStillBound(
                            saved.controlledText, saved.view.text,
                        )
                    }.forEach { saved ->
                        val view = saved.view
                        if (view.currentTextColor != inactiveColor)
                            view.setTextColor(inactiveColor)
                        // A stable uniform alpha avoids splitting one wrapped
                        // sentence into Apple-native per-fragment fade phases.
                        if (kotlin.math.abs(view.alpha - 1f) > 0.01f)
                            view.alpha = 1f
                        saved.hiddenByPulse = false
                    }
                }
                if (lastActive != active || lastForegroundReady != foregroundReady) {
                    AppleLyricUiRuntimeProbe.record(
                        if (active) "translation_painter_active" else "translation_painter_inactive",
                        "foregroundReady=${if (foregroundReady) 1 else 0} " +
                            "nativeVisible=${if (owners.nativeVisible) 1 else 0} " +
                            "foregroundVisible=${if (owners.foregroundVisible) 1 else 0} " +
                            "cells=${originals.size}",
                    )
                }
                lastActive = active
                lastForegroundReady = foregroundReady
            }
            // ~35 main-thread pulse callbacks per second were observed in
            // NPatch logs. Native source word animation stays untouched;
            // translation itself only changes between active and inactive.
            root.postDelayed(this, when {
                !visible -> 750L
                lastActive == true -> 100L
                else -> 360L
            })
        }
    }
}
