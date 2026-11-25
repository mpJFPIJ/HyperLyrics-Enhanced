package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Color
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

internal enum class AppleTranslationTone { ACTIVE, MUTED }

internal data class AppleTranslationPaintOwners(
    val nativeVisible: Boolean,
    val foregroundVisible: Boolean,
    val tone: AppleTranslationTone,
)

internal enum class AppleTranslationBindResult { REUSE, REPLACE, STOP }

internal data class AppleTranslationFocusCandidate(
    val adapterPosition: Int?,
    val beginMs: Long,
    val endMs: Long,
)

/** Visibility is relative to the bound holder, not window attachment or
 * alpha: the foreground itself intentionally sets native alpha to zero. */
internal data class AppleMeaningCellCandidate(
    val viewId: String?,
    val text: String,
    val visibleInHolder: Boolean,
)

/** NPatch loads Apple's AndroidX RecyclerView from the host classloader;
 * `view is RecyclerView` with the module class identity is always false. */
internal object AppleTranslationRecyclerOwnerPolicy {
    fun isRecyclerViewHierarchy(classNames: Iterable<String>): Boolean =
        classNames.any { it == "androidx.recyclerview.widget.RecyclerView" }

    fun isNativeRecyclerView(view: View): Boolean {
        val type: Class<*> = view.javaClass
        return isRecyclerViewHierarchy(
            generateSequence(type) { it.superclass }.map { it.name }.asIterable(),
        )
    }
}

/** One verified source-line interval owns the meaning's focus. Host active
 * positions corroborate overlapping intervals but may lag immediately after
 * a seek or be briefly empty during a RecyclerView update. */
internal object AppleTranslationFocusPolicy {
    fun select(
        rows: List<AppleTranslationFocusCandidate>,
        nativeActive: Set<Int>?,
        positionMs: Long?,
    ): Int? {
        if (positionMs != null) {
            // A remembered native line clock is valid only for an actual
            // holder position, not a source-text guess or stale placeholder.
            val timed = rows.indices.filter { index ->
                rows[index].adapterPosition != null &&
                    AppleNativeTranslationHighlightPolicy.lineActive(
                        positionMs, rows[index].beginMs, rows[index].endMs,
                    )
            }
            if (timed.isEmpty()) return null
            val corroborated = timed.filter { index ->
                rows[index].adapterPosition?.let { it in nativeActive.orEmpty() } == true
            }
            val choices = corroborated.ifEmpty { timed }
            return choices.maxWithOrNull(compareBy<Int> { rows[it].beginMs }
                .thenBy { rows[it].adapterPosition ?: -1 })
        }
        // If the player clock is unavailable, an unambiguous host active row
        // is the only safe fallback. Never choose a future row from an
        // arbitrary multi-active set without knowing the playback position.
        return rows.indices.filter { index ->
            rows[index].adapterPosition?.let { it in nativeActive.orEmpty() } == true
        }.singleOrNull()
    }
}

/** The last native translation getter can still describe the OUTGOING song.
 * A retained holder must not borrow the new player's clock until both the
 * presentation and indexed line occurrence own that same song/revision. */
internal object AppleTranslationLivePresentationPolicy {
    fun canFocus(
        pulseSongId: String,
        pulseRevision: Long,
        presentedSongId: String?,
        presentedRevision: Long,
        indexedSongId: String?,
        indexedRevision: Long,
    ): Boolean = presentedSongId != null &&
        pulseSongId == presentedSongId && pulseRevision == presentedRevision &&
        pulseSongId == indexedSongId && pulseRevision == indexedRevision
}

/** A holder's bind entry invalidates cached source data, not its live painter. */
internal class AppleTranslationBindingLifecycle {
    private var generation = 0L
    private var installed: AppleTranslationLineContext? = null
    private var pending: AppleTranslationLineContext? = null

    fun painterInstalled(context: AppleTranslationLineContext) {
        installed = context
        pending = context
    }

    fun painterStopped() {
        installed = null
        pending = null
    }

    fun beginHolderBind(): Long {
        generation++
        pending = installed
        return generation
    }

    fun hasPendingPainter(): Boolean = pending != null

    fun completeHolderBind(
        bindingGeneration: Long,
        context: AppleTranslationLineContext?,
        sameViews: Boolean,
        controlledTextsIntact: Boolean,
    ): AppleTranslationBindResult {
        if (bindingGeneration != generation) return AppleTranslationBindResult.STOP
        val retained = pending
        val result = when {
            context == null -> AppleTranslationBindResult.STOP
            retained != null && AppleNativeTranslationHighlightPolicy.canReusePulse(
                retained, context, sameViews, controlledTextsIntact,
            ) -> AppleTranslationBindResult.REUSE
            else -> AppleTranslationBindResult.REPLACE
        }
        if (result != AppleTranslationBindResult.REUSE) painterStopped()
        return result
    }
}

/** Keep all wrapped Korean translation text on the SAME Apple source-line clock. */
internal object AppleNativeTranslationHighlightPolicy {
    /** Keep one foreground painter through active/inactive color changes. */
    fun paintOwners(lineActive: Boolean, foregroundReady: Boolean): AppleTranslationPaintOwners {
        return AppleTranslationPaintOwners(
            nativeVisible = !foregroundReady,
            foregroundVisible = foregroundReady,
            tone = if (lineActive) AppleTranslationTone.ACTIVE else AppleTranslationTone.MUTED,
        )
    }

    /** Exact repeated binds must not briefly restore the old native painter. */
    fun canReusePulse(
        existingContext: AppleTranslationLineContext,
        requestedContext: AppleTranslationLineContext,
        sameViews: Boolean,
        controlledTextsIntact: Boolean,
    ): Boolean = existingContext == requestedContext && sameViews && controlledTextsIntact

    /** A pending translation has no new getter context yet. A recycled holder
     * must not keep its previous meaning merely because the song and cache
     * revision still match. A stable native adapter position is the minimum
     * proof of row identity; observed Japanese cells must also agree when
     * available. Unknown positions fail closed, including repeated choruses. */
    fun mayKeepPendingPainter(
        pending: AppleTranslationLineContext,
        boundAdapterPosition: Int?,
        currentSongId: String?,
        currentRevision: Long,
        currentAdapterPosition: Int?,
        observedSourceCells: List<String>,
        boundAdapterOwner: Any? = null,
        currentAdapterOwner: Any? = null,
        displayedSource: String = pending.sourceText,
    ): Boolean {
        if (pending.songId != currentSongId || pending.revision != currentRevision ||
            boundAdapterPosition == null || boundAdapterPosition < 0 ||
            currentAdapterPosition != boundAdapterPosition ||
            boundAdapterOwner == null || boundAdapterOwner !== currentAdapterOwner
        ) return false
        return !sourceCellsHaveReadableText(observedSourceCells) ||
            sourceCellsMatchExactly(pending.sourceText, observedSourceCells, displayedSource)
    }

    /** The native observer collects song_lyrics_word cells only. A matching
     * prefix (including a Japanese + Latin continuation) is not one source
     * line. Do not equate a string prefix with an occurrence identity. */
    fun sourceCellsHaveReadableText(sourceCells: List<String>): Boolean =
        AppleLineWidePronunciationRowPolicy.sourceKey(
            sourceCells.joinToString(separator = ""),
        ).isNotEmpty()

    fun sourceCellsMatchExactly(
        source: String,
        sourceCells: List<String>,
        displayedSource: String = source,
    ): Boolean {
        val expected = AppleLineWidePronunciationRowPolicy.sourceKey(displayedSource)
        val actual = AppleLineWidePronunciationRowPolicy.sourceKey(
            sourceCells.joinToString(separator = ""),
        )
        val raw = AppleLineWidePronunciationRowPolicy.sourceKey(source)
        return actual.isNotEmpty() && (actual == expected || actual == raw)
    }

    /** Host word getters transform each word individually. Whole-line
     * Traditional→Simplified phrase conversion is not equivalent when the
     * native karaoke model splits a dictionary phrase across word cells. */
    fun displayedNativeWordSource(
        originalLine: String,
        rawWords: List<String>,
        displayWord: (String) -> String,
    ): String? {
        if (rawWords.isEmpty()) return null
        val originalKey = AppleLineWidePronunciationRowPolicy.sourceKey(originalLine)
        val wordKey = AppleLineWidePronunciationRowPolicy.sourceKey(
            rawWords.joinToString(separator = ""),
        )
        if (originalKey.isEmpty() || wordKey != originalKey) return null
        return rawWords.joinToString(separator = "", transform = displayWord)
    }

    /** Missing per-holder getter: only an already timed occurrence at THIS
     * adapter position can supply a fallback context, never a global
     * same-source cache (which cannot distinguish repeated choruses). */
    fun verifiedFallbackContext(
        byPosition: Map<Int, AppleTranslationLineContext>,
        indexedSongId: String?,
        indexedRevision: Long,
        currentSongId: String?,
        currentRevision: Long,
        adapterPosition: Int?,
        observedSourceCells: List<String>,
        displaySource: (AppleTranslationLineContext) -> String = { it.sourceText },
    ): AppleTranslationLineContext? {
        if (adapterPosition == null || adapterPosition < 0 ||
            indexedSongId != currentSongId || indexedRevision != currentRevision
        ) return null
        return byPosition[adapterPosition]?.takeIf { candidate ->
            candidate.songId == currentSongId && candidate.revision == currentRevision &&
                sourceCellsMatchExactly(
                    candidate.sourceText, observedSourceCells,
                    displaySource(candidate),
                )
        }
    }

    /** When layout waits for native translation cells, a genuinely new lyric
     * must retire the previous painter immediately, including on retries. */
    fun mustStopPendingForContext(
        pending: AppleTranslationLineContext,
        requested: AppleTranslationLineContext,
        boundAdapterPosition: Int?,
        currentSongId: String?,
        currentRevision: Long,
        currentAdapterPosition: Int?,
        observedSourceCells: List<String>,
        boundAdapterOwner: Any? = null,
        currentAdapterOwner: Any? = null,
        displayedSource: String = pending.sourceText,
    ): Boolean = pending != requested || !mayKeepPendingPainter(
        pending, boundAdapterPosition, currentSongId, currentRevision,
        currentAdapterPosition, observedSourceCells,
        boundAdapterOwner, currentAdapterOwner, displayedSource,
    )

    fun eligibleKoreanMeaning(translationVisible: Boolean, koreanTargetSelected: Boolean): Boolean =
        translationVisible && koreanTargetSelected

    /** The latest started native source window is the ONE highlighted meaning. */
    fun activeRowIndex(
        rows: List<AppleTranslationLineContext>,
        positionMs: Long?,
    ): Int? {
        if (positionMs == null) return null
        return rows.indices
            .filter { lineActive(positionMs, rows[it].beginMs, rows[it].endMs) }
            .maxWithOrNull(compareBy<Int> { rows[it].beginMs }.thenByDescending { it })
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
        if (normalizedId == "lyrics_translation_line") {
            val expected = compact(translation)
            val actual = compact(candidate)
            // The native ID supplies the missing ownership proof. A real
            // one-syllable meaning (e.g. "나") must not lose its focus.
            return actual.isNotEmpty() && expected.contains(actual)
        }
        return normalizedId.isEmpty() &&
            compact(candidate).length >= 2 &&
            compact(candidate) == compact(translation)
    }

    /** Candidate fragments are only hints for locating the native meaning
     * cells. Before assigning the new source clock, their COMPLETE ordered
     * text must equal its expected meaning. Substring matches alone can
     * attach an old cell ("나") to a new line ("나는"). */
    fun verifiedCompleteMeaning(
        translation: String,
        orderedMeaningFragments: List<String>,
    ): Boolean {
        val expected = compact(translation)
        if (expected.isEmpty() || orderedMeaningFragments.isEmpty()) return false
        return compact(orderedMeaningFragments.joinToString(separator = "")) == expected
    }

    /** An explicit native meaning ID is more trustworthy than substring
     * filtering: include ALL visible ID-owned cells, including stale ones,
     * so incomplete or duplicated transitions cannot pass coverage. Only
     * when no named meaning exists may an exact anonymous cell stand in. */
    fun selectMeaningCellIndices(
        translation: String,
        candidates: List<AppleMeaningCellCandidate>,
    ): List<Int> {
        val shown = candidates.indices.filter { candidates[it].visibleInHolder }
        val named = shown.filter { candidates[it].viewId == "lyrics_translation_line" }
        if (named.isNotEmpty()) return named
        return shown.filter { index ->
            candidates[index].viewId.isNullOrEmpty() && shouldRenderTranslationCell(
                null, translation, candidates[index].text,
            )
        }
    }

    fun fragmentMatches(translation: String, candidate: String): Boolean {
        val expected = compact(translation)
        val actual = compact(candidate)
        // A one-letter candidate may be a Japanese source word or a rebinding
        // placeholder. Prefer actual translated fragments; never alter source.
        return actual.length >= 2 && expected.contains(actual)
    }
}

/** A native adapter's row number is meaningful only inside THAT adapter.
 * Keep adapter keys weak so a retired Apple lyrics presentation is collectible.
 * There is no timing fallback from a matching source in a different owner. */
internal class AppleTranslationOccurrenceIndex {
    private val byAdapter = WeakIdentityMap<Any, MutableMap<Int, AppleTranslationLineContext>>()
    @Volatile var indexedSongId: String? = null
        private set
    @Volatile var indexedRevision: Long = -1L
        private set

    @Synchronized fun remember(
        ctx: AppleTranslationLineContext,
        adapterOwner: Any?,
        adapterPosition: Int?,
    ) {
        if (indexedSongId != ctx.songId || indexedRevision != ctx.revision) {
            byAdapter.clear()
            indexedSongId = ctx.songId
            indexedRevision = ctx.revision
        }
        if (adapterOwner == null || adapterPosition == null || adapterPosition < 0) return
        val rows = byAdapter[adapterOwner] ?: LinkedHashMap()
        if (rows.size > 512) rows.clear()
        rows[adapterPosition] = ctx
        byAdapter[adapterOwner] = rows
    }

    @Synchronized fun verifiedFallback(
        currentSongId: String?,
        currentRevision: Long,
        adapterOwner: Any?,
        adapterPosition: Int?,
        observedSourceCells: List<String>,
        displaySource: (AppleTranslationLineContext) -> String = { it.sourceText },
    ): AppleTranslationLineContext? {
        val rows = adapterOwner?.let { byAdapter[it] } ?: return null
        return AppleNativeTranslationHighlightPolicy.verifiedFallbackContext(
            rows, indexedSongId, indexedRevision, currentSongId, currentRevision,
            adapterPosition, observedSourceCells, displaySource,
        )
    }
}

/** Only native translation getter code may write byHolder. Fallback resolves
 * one current attempt, and must be reconsidered against freshly observed
 * source cells on EVERY delayed bind, never promoted into getter provenance. */
internal class AppleTranslationContextResolver(
    private val byHolder: AppleTranslationLineContextStore,
    private val occurrenceIndex: AppleTranslationOccurrenceIndex,
) {
    fun resolve(
        holder: Any?,
        currentSongId: String?,
        revision: Long,
        adapterOwner: Any?,
        adapterPosition: Int?,
        observedSourceCells: List<String>,
        displaySource: (AppleTranslationLineContext) -> String = { ctx ->
            ctx.displayedSourceText ?: ctx.sourceText
        },
    ): AppleTranslationLineContext? = byHolder.forHolder(holder) ?:
        occurrenceIndex.verifiedFallback(
            currentSongId, revision, adapterOwner, adapterPosition,
            observedSourceCells, displaySource,
        )
}

internal class AppleNativeTranslationHighlight(
    private val nativeActivePositions: (View) -> Set<Int>? = { null },
    private val presentedSongId: () -> String? = { null },
    private val presentedRevision: () -> Long = { -1L },
) {
    private val byHolder = AppleTranslationLineContextStore()
    // A strong Pulse value owns its root and child TextViews; that defeats a
    // WeakHashMap key and leaks native prefetched holders that never attach.
    // Attached roots keep their Pulse alive through the attach listener.
    private val pulses = WeakHashMap<View, WeakReference<Pulse>>()
    private fun pulseAt(root: View): Pulse? = pulses[root]?.get()
    private fun livePulses(): List<Pulse> = pulses.values.mapNotNull { it.get() }
    private val bindingGenerations = WeakHashMap<View, Long>()
    private val bindingLifecycle = WeakHashMap<View, AppleTranslationBindingLifecycle>()
    private val occurrenceIndex = AppleTranslationOccurrenceIndex()
    private val contextResolver = AppleTranslationContextResolver(byHolder, occurrenceIndex)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private var refreshDeadlineMs = Long.MAX_VALUE
    private val sharedRefresh = object : Runnable {
        override fun run() {
            refreshScheduled = false
            refreshDeadlineMs = Long.MAX_VALUE
            val visible = refreshVisibleGroups()
            scheduleRefresh(if (visible) 100L else 400L)
        }
    }

    private fun scheduleRefresh(delayMs: Long = 0L) {
        // A RecyclerView can bind an offscreen holder that never attaches.
        // Such a pending bind must not keep a 400-ms UI timer alive forever.
        if (livePulses().none { it.isRunning && it.root.isAttachedToWindow }) return
        val deadline = SystemClock.uptimeMillis() + delayMs
        if (refreshScheduled && deadline >= refreshDeadlineMs) return
        if (refreshScheduled) mainHandler.removeCallbacks(sharedRefresh)
        refreshScheduled = true
        refreshDeadlineMs = deadline
        mainHandler.postDelayed(sharedRefresh, delayMs)
    }

    private fun stopRefreshIfIdle() {
        if (livePulses().any { it.isRunning && it.root.isAttachedToWindow }) return
        mainHandler.removeCallbacks(sharedRefresh)
        refreshScheduled = false
        refreshDeadlineMs = Long.MAX_VALUE
    }

    fun remember(
        holder: Any?,
        songId: String?,
        sourceText: String?,
        beginMs: Long,
        endMs: Long,
        translation: String,
        revision: Long,
        adapterPosition: Int? = null,
        adapterOwner: Any? = null,
        displayedSourceText: String? = null,
    ) {
        if (songId.isNullOrBlank() || sourceText.isNullOrBlank() ||
            beginMs < 0L || endMs <= beginMs || translation.isBlank()
        ) return
        val ctx = AppleTranslationLineContext(
            songId, sourceText, beginMs, endMs, translation, revision,
            displayedSourceText,
        )
        byHolder.remember(holder, ctx)
        occurrenceIndex.remember(ctx, adapterOwner, adapterPosition)
    }

    fun beginHolderBinding(holder: Any?, root: View?) {
        byHolder.clear(holder)
        root?.let { view ->
            // The native row may be rebound without changing any of its
            // content. Keep the overlay until completed bind can verify it.
            bindingLifecycle.getOrPut(view) { AppleTranslationBindingLifecycle() }
                .beginHolderBind()
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
        observeSourceCells: () -> List<String> = { observedSourceCells },
        holderAdapterPosition: Int? = null,
        adapterOwner: Any? = null,
        retry: Int = 0,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        root ?: return false
        if (!isCurrent()) return false
        // Keep an identical live row's foreground across repeated native
        // onBind callbacks. Restoring its old text and alpha on every bind
        // caused a one-frame white flash at the translation boundary.
        fun stopOldPulse(force: Boolean = false) {
            if (retry == 0 || force) {
                pulses.remove(root)?.get()?.stop()
                bindingLifecycle[root]?.painterStopped()
            }
        }
        val generation = bindingGenerations[root] ?: 0L
        fun retryAfterLayout() {
            if (retry >= 3 || !enabled) return
            root.postDelayed({
                if (bindingGenerations[root] == generation &&
                    root.isAttachedToWindow && isCurrent()
                ) bind(
                    holder, root, currentSongId, revision, enabled,
                    playbackPositionMs, observedSourceCells = observeSourceCells(),
                    observeSourceCells = observeSourceCells,
                    holderAdapterPosition = holderAdapterPosition,
                    adapterOwner = adapterOwner,
                    retry = retry + 1, isCurrent = isCurrent,
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
        val ctx = contextResolver.resolve(
            holder, currentSongId, revision, adapterOwner, holderAdapterPosition,
            observedSourceCells,
            displaySource = { candidate ->
                candidate.displayedSourceText ?:
                    (AppleLyricTextTransform.transform(candidate.sourceText)
                        ?: candidate.sourceText)
            },
        )
        if (ctx == null) {
            // Apple may invoke a redundant bind before its translation getter.
            // Keep the existing painter only while the same host text is intact.
            val pending = pulseAt(root)
            val keepPending = enabled &&
                pending?.isSafeFor(
                    currentSongId, revision, holderAdapterPosition, adapterOwner,
                    observedSourceCells,
                ) == true
            if (!keepPending) {
                // A later retry can first observe the recycled native source
                // after the initial pass saw no cells. Never preserve the
                // previous row's foreground just because retry > 0.
                if (pending != null) AppleLyricUiRuntimeProbe.record(
                    "translation_pending_rebind_rejected",
                    "oldAdapterKnown=${if (pending.adapterPosition != null) 1 else 0} " +
                        "newAdapterKnown=${if (holderAdapterPosition != null) 1 else 0} " +
                        "samePosition=${if (pending.adapterPosition == holderAdapterPosition) 1 else 0} " +
                        "sourceCells=${observedSourceCells.size} retry=$retry",
                    root,
                )
                stopOldPulse(force = true)
            }
            AppleLyricUiRuntimeProbe.record(
                "translation_context_missing",
                "holderPresent=${if (holder != null) 1 else 0} " +
                    "sourceCellCount=${observedSourceCells.size} retry=$retry",
                root,
            )
            retryAfterLayout()
            return keepPending
        }
        if (!enabled || ctx.songId != currentSongId || ctx.revision != revision) {
            stopOldPulse(force = true)
            return false
        }
        val old = pulseAt(root)
        if (old != null && (!old.isSafeFor(
                currentSongId, revision, holderAdapterPosition, adapterOwner,
                observedSourceCells,
            ) || AppleNativeTranslationHighlightPolicy.mustStopPendingForContext(
                old.ctx, ctx, old.adapterPosition, currentSongId, revision,
                holderAdapterPosition, observedSourceCells, old.adapterOwner,
                adapterOwner, old.ctx.displayedSourceText ?:
                    (AppleLyricTextTransform.transform(old.ctx.sourceText)
                        ?: old.ctx.sourceText),
            ))
        ) stopOldPulse(force = true)

        // Opaque/empty word views are not contradictory proof. However, when
        // actual readable Japanese cells disagree with a context (even a
        // fresh getter while native views are still from the previous bind),
        // never install a painter on the old cells. Displayed source respects
        // the exact word split under optional Traditional→Simplified mode.
        if (AppleNativeTranslationHighlightPolicy.sourceCellsHaveReadableText(
                observedSourceCells,
            ) &&
            !AppleNativeTranslationHighlightPolicy.sourceCellsMatchExactly(
                ctx.sourceText, observedSourceCells,
                ctx.displayedSourceText ?:
                    (AppleLyricTextTransform.transform(ctx.sourceText) ?: ctx.sourceText),
            )
        ) {
            AppleLyricUiRuntimeProbe.record(
                "translation_source_mismatch",
                "sourceCellCount=${observedSourceCells.size} retry=$retry",
                root,
            )
            retryAfterLayout()
            return false
        }
        fun idName(view: View): String? = runCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrNull()
        fun visibleMeaningPath(cell: View): Boolean {
            var parent: View? = cell
            while (parent != null) {
                // Ignore alpha and window attachment: a prefetched row may
                // be detached, and our own pulse sets native alpha to 0.
                if (parent.visibility != View.VISIBLE) return false
                if (parent !== cell && idName(parent).orEmpty()
                        .contains("pronunciation", ignoreCase = true)
                ) return false
                if (parent === root) return true
                parent = parent.parent as? View
            }
            return false
        }
        val texts = mutableListOf<TextView>()
        val meaningCandidates = mutableListOf<AppleMeaningCellCandidate>()
        fun scan(view: View, depth: Int) {
            if (depth > 12) return
            if (view is TextView) {
                // The injected Hangul ReadingView is not a Korean meaning
                // line, even if its text happens to occur in the translation.
                if (view.javaClass.simpleName == "ReadingView") return
                val entry = idName(view)
                val content = view.text.toString()
                if (entry == "lyrics_translation_line" ||
                    (entry.isNullOrEmpty() &&
                        AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
                            null, ctx.translation, content,
                        ))
                ) {
                    texts.add(view)
                    meaningCandidates.add(AppleMeaningCellCandidate(
                        entry, content, visibleMeaningPath(view),
                    ))
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
            }
        }
        scan(root, 0)
        if (texts.isEmpty()) {
            stopOldPulse()
            AppleLyricUiRuntimeProbe.record(
                "translation_cell_missing",
                "candidateTextLen=${ctx.translation.length} retry=$retry",
                root,
            )
            retryAfterLayout()
            return false
        }

        // Named native meaning cells take precedence. Inspect *all* named
        // visible fragments, including old ones whose text differs, so no
        // partial selection can be mistaken for complete new translation.
        val translationView = AppleNativeTranslationHighlightPolicy.selectMeaningCellIndices(
            ctx.translation, meaningCandidates,
        ).map(texts::get)
        if (translationView.isEmpty()) {
            stopOldPulse()
            retryAfterLayout()
            return false
        }
        if (!AppleNativeTranslationHighlightPolicy.verifiedCompleteMeaning(
                ctx.translation, translationView.map { it.text.toString() },
            )
        ) {
            // A newly arriving translated line can share a prefix with the
            // previous one. Do not paint an incomplete/duplicated native
            // meaning using the new Japanese lyric's time window.
            stopOldPulse(force = true)
            AppleLyricUiRuntimeProbe.record(
                "translation_cells_incomplete",
                "matchedCells=${translationView.size} retry=$retry",
                root,
            )
            retryAfterLayout()
            return false
        }
        val existing = pulseAt(root)
        val lifecycle = bindingLifecycle.getOrPut(root) { AppleTranslationBindingLifecycle() }
        val canReuse = existing?.matches(
            ctx, translationView, holderAdapterPosition, adapterOwner,
        ) == true
        val lifecycleResult = lifecycle.completeHolderBind(
            bindingGenerations[root] ?: 0L, ctx,
            sameViews = canReuse,
            controlledTextsIntact = canReuse,
        )
        if (canReuse && lifecycleResult == AppleTranslationBindResult.REUSE) {
            existing?.updatePlaybackPosition(playbackPositionMs)
            scheduleRefresh()
            return true
        }
        pulses.remove(root)?.get()?.stop()
        lifecycle.painterStopped()
        AppleLyricUiRuntimeProbe.record(
            "translation_cells_bound",
            "matchedCells=${translationView.size} " +
                "lineDurationMs=${ctx.endMs - ctx.beginMs} retry=$retry",
            root,
        )
        val pulse = Pulse(
            root, ctx, holderAdapterPosition, adapterOwner,
            translationView, playbackPositionMs,
            onScheduleRefresh = { scheduleRefresh() },
            onDetach = { old, detached ->
                if (pulseAt(detached) === old) pulses.remove(detached)
                bindingLifecycle[detached]?.painterStopped()
                stopRefreshIfIdle()
            },
        )
        pulses[root] = WeakReference(pulse)
        lifecycle.painterInstalled(ctx)
        pulse.start()
        scheduleRefresh()
        return true
    }

    private fun groupOwner(root: View): View {
        var ancestor: View? = root
        while (ancestor != null) {
            if (AppleTranslationRecyclerOwnerPolicy.isNativeRecyclerView(ancestor)) return ancestor
            ancestor = ancestor.parent as? View
        }
        return root.rootView
    }

    /** One runnable per entire presentation, not one runnable per bound row.
     * The old per-holder timers each walked all pulses, making the amount of
     * UI work grow quadratically with visible lyric row count. */
    private fun refreshVisibleGroups(): Boolean {
        val snapshot = livePulses()
        val currentSong = presentedSongId()
        val currentRevision = presentedRevision()
        snapshot.filter { stale ->
            !stale.textsIntact() || stale.ctx.songId != currentSong ||
                stale.ctx.revision != currentRevision
        }.forEach { stale ->
            // The native holder may still be attached during a track swap.
            // Remove its old foreground before the new playback clock can
            // accidentally focus the outgoing song's meaning.
            stale.stop()
            if (pulseAt(stale.root) === stale) {
                pulses.remove(stale.root)
                bindingLifecycle[stale.root]?.painterStopped()
            }
        }
        val attached = snapshot.filter { pulse ->
            pulse.isRunning && pulse.root.isAttachedToWindow && pulse.textsIntact()
        }
        if (attached.isEmpty()) return false
        attached.groupBy(::groupOwnerOfPulse).forEach { (owner, rows) ->
            // A recycled native holder can temporarily coexist with its
            // previous revision. There must be just one focused meaning for
            // this entire RecyclerView, never one per revision group.
            val songRows = rows.filter { pulse ->
                AppleTranslationLivePresentationPolicy.canFocus(
                    pulseSongId = pulse.ctx.songId,
                    pulseRevision = pulse.ctx.revision,
                    presentedSongId = currentSong,
                    presentedRevision = currentRevision,
                    indexedSongId = occurrenceIndex.indexedSongId,
                    indexedRevision = occurrenceIndex.indexedRevision,
                )
            }
            val nativeActive = if (AppleTranslationRecyclerOwnerPolicy.isNativeRecyclerView(owner))
                nativeActivePositions(owner) else null
            val selected = AppleTranslationFocusPolicy.select(
                rows = songRows.map { pulse ->
                    AppleTranslationFocusCandidate(
                        pulse.adapterPosition, pulse.ctx.beginMs, pulse.ctx.endMs,
                    )
                },
                nativeActive = nativeActive,
                positionMs = songRows.firstOrNull()?.currentPlaybackPosition(),
            )
            rows.forEach { pulse ->
                pulse.render(selected != null && songRows.getOrNull(selected) === pulse)
            }
        }
        return attached.any { it.root.isShown }
    }

    private fun groupOwnerOfPulse(pulse: Pulse): View = groupOwner(pulse.root)

    private class Pulse(
        val root: View,
        val ctx: AppleTranslationLineContext,
        val adapterPosition: Int?,
        val adapterOwner: Any?,
        views: List<TextView>,
        private var playbackPositionMs: () -> Long?,
        private val onScheduleRefresh: () -> Unit,
        private val onDetach: (Pulse, View) -> Unit,
    ) : View.OnAttachStateChangeListener {
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
        val isRunning: Boolean get() = running
        private var lastActive: Boolean? = null
        private var lastForegroundReady: Boolean? = null
        private val bounds = Rect()
        private var aboveNativeGradient: AppleKoreanTranslationForeground? = null

        fun matches(
            candidate: AppleTranslationLineContext,
            cells: List<TextView>,
            candidateAdapterPosition: Int?,
            candidateAdapterOwner: Any?,
        ): Boolean {
            val sameViews = originals.size == cells.size &&
                originals.indices.all { index -> originals[index].view === cells[index] }
            val intact = originals.all { saved ->
                AppleNativeTranslationHighlightPolicy.isStillBound(
                    saved.controlledText, saved.view.text,
                )
            }
            return running && adapterPosition == candidateAdapterPosition &&
                adapterOwner != null && adapterOwner === candidateAdapterOwner &&
                AppleNativeTranslationHighlightPolicy.canReusePulse(
                ctx, candidate, sameViews, intact,
            )
        }

        fun updatePlaybackPosition(next: () -> Long?) {
            playbackPositionMs = next
        }

        fun currentPlaybackPosition(): Long? = playbackPositionMs()

        fun textsIntact(): Boolean = originals.all { saved ->
            AppleNativeTranslationHighlightPolicy.isStillBound(
                saved.controlledText, saved.view.text,
            )
        }

        fun isSafeFor(
            songId: String?,
            revision: Long,
            currentAdapterPosition: Int?,
            currentAdapterOwner: Any?,
            observedSourceCells: List<String>,
        ): Boolean = running && textsIntact() &&
            AppleNativeTranslationHighlightPolicy.mayKeepPendingPainter(
                pending = ctx,
                boundAdapterPosition = adapterPosition,
                currentSongId = songId,
                currentRevision = revision,
                currentAdapterPosition = currentAdapterPosition,
                observedSourceCells = observedSourceCells,
                boundAdapterOwner = adapterOwner,
                currentAdapterOwner = currentAdapterOwner,
                displayedSource = ctx.displayedSourceText ?:
                    (AppleLyricTextTransform.transform(ctx.sourceText)
                        ?: ctx.sourceText),
            )

        private fun attachForeground(color: Int): Boolean {
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
                    root, originals.map { it.view }, color,
                )
                drawable.setBounds(0, 0, root.width, root.height)
                if (runCatching { root.overlay.add(drawable) }.isFailure) return false
                aboveNativeGradient = drawable
            }
            aboveNativeGradient?.let { drawable ->
                if (drawable.bounds.width() != root.width ||
                    drawable.bounds.height() != root.height
                ) drawable.setBounds(0, 0, root.width, root.height)
                drawable.setForegroundColor(color)
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
        }

        fun stop() {
            if (!running) return
            running = false
            root.removeOnAttachStateChangeListener(this)
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
            if (running) onScheduleRefresh()
        }

        override fun onViewDetachedFromWindow(v: View) {
            // Detach must remove the map entry: a WeakHashMap with a value
            // pointing back to its View would otherwise retain the entire
            // native karaoke row, its translation and attached TextViews.
            stop()
            onDetach(this, v)
        }

        fun render(active: Boolean) {
            if (!running || !textsIntact()) return
            val visible = root.isShown && root.getGlobalVisibleRect(bounds)
            if (visible) {
                // Both states keep the same drawable, changing its color
                // only. Switching between native and overlay at the line
                // boundary previously caused a visible one-frame flash.
                val nativeColor = if (active) Color.WHITE else inactiveColor
                val foregroundReady = attachForeground(nativeColor)
                val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
                    lineActive = active, foregroundReady = foregroundReady,
                )
                originals.filter { saved ->
                    AppleNativeTranslationHighlightPolicy.isStillBound(
                        saved.controlledText, saved.view.text,
                    )
                }.forEach { saved ->
                    val view = saved.view
                    // The native host can repaint its word gradient after we
                    // set the text color. Keep it hidden while the stable
                    // outer foreground paints white (active) or grey (other).
                    if (view.currentTextColor != nativeColor)
                        view.setTextColor(nativeColor)
                    val nativeAlpha = if (owners.nativeVisible) 1f else 0f
                    if (kotlin.math.abs(view.alpha - nativeAlpha) > 0.01f)
                        view.alpha = nativeAlpha
                    saved.hiddenByPulse = !owners.nativeVisible
                }
                if (lastActive != active || lastForegroundReady != foregroundReady) {
                    AppleLyricUiRuntimeProbe.record(
                        if (active) "translation_painter_active" else "translation_painter_inactive",
                        "foregroundReady=${if (foregroundReady) 1 else 0} " +
                            "nativeVisible=${if (owners.nativeVisible) 1 else 0} " +
                            "foregroundVisible=${if (owners.foregroundVisible) 1 else 0} " +
                            "tone=${owners.tone} " +
                            "cells=${originals.size}",
                    )
                }
                lastActive = active
                lastForegroundReady = foregroundReady
            }
        }
    }
}
