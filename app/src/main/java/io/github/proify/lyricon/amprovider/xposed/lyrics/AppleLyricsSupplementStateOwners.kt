/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.reflect.Executable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * De-duplicates one hook-install family by method identity.
 *
 * The backing set stays a [ConcurrentHashMap.newKeySet]; callers receive only the
 * first-install decision and can no longer reach or clear the collection.
 */
internal class AppleLyricsMethodInstallDedup {
    private val methods = ConcurrentHashMap.newKeySet<Executable>()

    /** Returns true the first time [method] is registered. */
    fun markInstalled(method: Executable): Boolean = methods.add(method)
}

/**
 * Owns the pronunciation language, per-lyric-object context, one-shot render plan and
 * render-scope state.
 *
 * Container kinds are preserved verbatim: concurrent song map, weak identity map,
 * identity-keyed synchronized map (with its 256-entry reset) and a nested thread-local
 * stack. No operation blocks or joins; every callback stays outside the monitors.
 */
internal class AppleLyricsPronunciationState {
    private val languagesBySongId = ConcurrentHashMap<String, List<String>>()
    private val contextByLyricObject = WeakIdentityMap<Any, ApplePronunciationContext>()
    private val pendingRenderPlans = Collections.synchronizedMap(
        IdentityHashMap<Any, ApplePronunciationRenderPlan>()
    )
    private val wordRenderContexts = ThreadLocalStack<ApplePronunciationWordRenderContext>()
    private val lineWideByHolder = WeakIdentityMap<Any, AppleLineWidePronunciationPayload>()
    private val lineWideBySource = LinkedHashMap<String, AppleLineWidePronunciationPayload>()
    private val ambiguousLineWideSources = HashSet<String>()
    private var lineWideSongId: String? = null
    private var lineWideRevision: Long = -1L

    fun rememberLanguages(songId: String, languages: List<String>) {
        languagesBySongId[songId] = languages
    }

    fun languages(songId: String): List<String>? = languagesBySongId[songId]

    fun putContext(lyricObject: Any, context: ApplePronunciationContext) {
        contextByLyricObject[lyricObject] = context
    }

    fun contextFor(lyricObject: Any): ApplePronunciationContext? =
        contextByLyricObject[lyricObject]

    /** Registers a one-shot plan for [vector]; the caps and reset threshold are unchanged. */
    fun registerRenderPlan(vector: Any, plan: ApplePronunciationRenderPlan) {
        synchronized(pendingRenderPlans) {
            if (pendingRenderPlans.size >= MAX_RENDER_PLANS) {
                pendingRenderPlans.clear()
            }
            pendingRenderPlans[vector] = plan
        }
    }

    /** Consumes the plan registered for [vector], if any. */
    fun consumeRenderPlan(vector: Any): ApplePronunciationRenderPlan? =
        synchronized(pendingRenderPlans) {
            pendingRenderPlans.remove(vector)
        }

    fun clearRenderPlans() {
        synchronized(pendingRenderPlans) {
            pendingRenderPlans.clear()
        }
    }

    fun currentWordRenderContext(): ApplePronunciationWordRenderContext? =
        wordRenderContexts.current

    fun pushWordRenderContext(context: ApplePronunciationWordRenderContext) {
        wordRenderContexts.push(context)
    }

    fun popWordRenderContext() {
        wordRenderContexts.pop()
    }

    fun beginHolderBinding(holder: Any?) {
        holder?.let(lineWideByHolder::remove)
    }

    /** No strong references to Apple ViewHolders escape this method. */
    fun rememberLineWide(
        payload: AppleLineWidePronunciationPayload,
        holder: Any?,
    ) {
        holder?.let { lineWideByHolder[it] = payload }
        synchronized(lineWideBySource) {
            if (lineWideSongId != payload.songId || lineWideRevision != payload.revision ||
                lineWideBySource.size >= MAX_RENDER_PLANS
            ) {
                lineWideBySource.clear()
                ambiguousLineWideSources.clear()
                lineWideSongId = payload.songId
                lineWideRevision = payload.revision
            }
            val source = AppleLineWidePronunciationRowPolicy.sourceKey(payload.sourceText)
            if (source.isBlank() || source in ambiguousLineWideSources) return@synchronized
            val previous = lineWideBySource[source]
            if (previous != null && previous.pronunciation != payload.pronunciation) {
                lineWideBySource.remove(source)
                ambiguousLineWideSources.add(source)
            } else {
                lineWideBySource[source] = payload
            }
        }
    }

    fun lineWideForHolder(
        holder: Any?,
        songId: String?,
        observedSourceText: String?,
        revision: Long,
    ): AppleLineWidePronunciationPayload? {
        val pending = holder?.let { lineWideByHolder[it] }
        if (pending != null && pending.songId == songId && pending.revision == revision) {
            return pending
        }
        if (songId == null || observedSourceText.isNullOrBlank()) return null
        synchronized(lineWideBySource) {
            if (lineWideSongId != songId || lineWideRevision != revision) return null
            return lineWideBySource[
                AppleLineWidePronunciationRowPolicy.sourceKey(observedSourceText)
            ]
        }
    }

    internal companion object {
        /** Kept equal to the pre-migration inline cap in registerApplePronunciationRenderPlan. */
        const val MAX_RENDER_PLANS = 256
    }
}

/**
 * Owns the "已处理过该 View" identity sets used by the loading-overlay and translation-button
 * suppression paths. Identity semantics rely on View not overriding equals/hashCode.
 */
internal class AppleLyricsViewTracking {
    private val suppressedLoadingViews =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val forcedTranslationButtons =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun markLoadingViewSuppressedIfNew(view: Any): Boolean = suppressedLoadingViews.add(view)

    fun markTranslationButtonForcedIfNew(view: Any): Boolean = forcedTranslationButtons.add(view)
}

/**
 * Debug-only signature de-duplication for the three pronunciation diagnostic streams.
 * No production decision reads these sets; release builds must not add entries here.
 */
internal class AppleLyricsPronunciationDiagnostics {
    private val songSnapshotSignatures = ConcurrentHashMap.newKeySet<String>()
    private val runtimeKeys = ConcurrentHashMap.newKeySet<String>()
    private val bindingKeys = ConcurrentHashMap.newKeySet<String>()

    fun markSongSnapshotLogged(signature: String): Boolean = songSnapshotSignatures.add(signature)

    fun markRuntimeReported(songId: String, dedupeKey: String): Boolean =
        runtimeKeys.add("$songId|$dedupeKey")

    fun markBindingReported(songId: String, dedupeKey: String): Boolean =
        bindingKeys.add("$songId|$dedupeKey")
}
