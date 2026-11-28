/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.animation.ValueAnimator
import java.lang.ref.WeakReference
import java.lang.reflect.Method

internal data class AppleLyricsHyperOsMethods(
    val setSelfBlur: Method?,
    val setSelfBlurType: Method?,
)

internal data class AppleLyricsBlurRuntimeState(
    var adapterRef: WeakReference<Any>? = null,
    /** The same host RecyclerView adapter can be retained across songs. */
    var presentationSongId: String? = null,
    var presentationRevision: Long? = null,
    var recyclerHeight: Int = 0,
    var settledAnchorTopY: Float? = null,
    /** Set only by a user drag. Programmatic requests never enter browse mode. */
    var suspendedForScroll: Boolean = false,
    /** Clear blur once per motion period without mistaking auto-follow for browsing. */
    var motionBlurCleared: Boolean = false,
    var pendingProgrammaticRecenterPosition: Int? = null,
    var lastActivePositions: Set<Int> = emptySet(),
    var lastFocusedActivePosition: Int? = null,
    var lastFollowRecoveryAtMs: Long = 0L,
    /** A native-follow check that survives one unsettled or empty viewport sample. */
    var pendingNativeFollowCheck: AppleLyricsFollowRecheck? = null,
    var pendingOutgoingPositions: Set<Int> = emptySet(),
    var outgoingZoneTopByPosition: Map<Int, Float> = emptyMap(),
    var pendingApplyBlur: Runnable? = null,
    /** Independent of visual blur; monitors a stable multi-row native clock overlap. */
    var pendingOverlapFocusPoll: Runnable? = null,
    var lastDiagnosticSignature: String? = null,
    var blurMode: Int? = null,
    var currentBlurRadius: Float = 0f,
    var targetBlurRadius: Float = 0f,
    var blurAnimator: ValueAnimator? = null,
)

internal data class AppleLyricsFollowRecheck(
    val adapterRef: WeakReference<Any>,
    val songId: String,
    val active: Set<Int>,
    val notBeforeMs: Long,
    val revision: Long = -1L,
) {
    /** Main-thread only; excluded from identity so callbacks can compare the pending instance. */
    var transientRecheckCount: Int = 0
    var transientRecheckQueued: Boolean = false
    /** Reflection returning normally is a sent scroll request, not a visible row. */
    var dispatchedTargetPosition: Int? = null
    var dispatchedAtMs: Long = 0L
    var dispatchConfirmationQueued: Boolean = false
    var dispatchConfirmationDelivered: Boolean = false
    var dispatchUnconfirmedReported: Boolean = false
}

/** Track the exact adapter and active row across native layout/idle events.
 * A delayed callback alone can miss the row while Apple's RecyclerView settles. */
internal object AppleLyricsFollowRecheckPolicy {
    const val NATIVE_SETTLE_MS = 750L
    private const val DISPATCH_CONFIRMATION_MS = 750L
    private const val MAX_TRANSIENT_RECHECK_WINDOW_MS = 2_500L
    private val TRANSIENT_RECHECK_DELAYS_MS = longArrayOf(100L, 200L, 400L, 500L)

    /** A second active row does not prove which row owns playback. Recovery
     * may target only Apple's current native clock focus, never max(row). */
    fun trustedTargetPosition(
        nativeActive: Set<Int>, nativeFocusedPosition: Int?,
    ): Int? = nativeFocusedPosition?.takeIf(nativeActive::contains)

    /** A visible outgoing row cannot settle an offscreen incoming focus.
     * After dispatch, confirm the exact requested target, not a sibling. */
    fun hasVisibleOwnedRow(
        nativeActive: Set<Int>, visible: Set<Int>, trustedFocus: Int?,
        dispatchedTarget: Int?,
    ): Boolean = (dispatchedTarget ?: trustedFocus)?.let { target ->
        target in nativeActive && target in visible
    } ?: false

    fun begin(
        adapter: Any, songId: String?, active: Set<Int>, nowMs: Long,
        revision: Long = -1L,
    ): AppleLyricsFollowRecheck? {
        if (active.isEmpty() || songId.isNullOrBlank()) return null
        return AppleLyricsFollowRecheck(
            WeakReference(adapter), songId, active.toSet(), nowMs + NATIVE_SETTLE_MS,
            revision = revision,
        )
    }

    fun isStale(
        pending: AppleLyricsFollowRecheck,
        adapter: Any?,
        songId: String?,
        active: Set<Int>?,
        userBrowsing: Boolean,
        revision: Long = -1L,
    ): Boolean = userBrowsing || songId != pending.songId ||
        revision != pending.revision || adapter == null ||
        pending.adapterRef.get() !== adapter ||
        // Failed host reflection is unknown, not a real empty active set.
        (active != null && pending.active != active)

    fun shouldEvaluate(
        pending: AppleLyricsFollowRecheck,
        adapter: Any?,
        songId: String?,
        active: Set<Int>?,
        nowMs: Long,
        revision: Long = -1L,
    ): Boolean = active != null &&
        !isStale(pending, adapter, songId, active, userBrowsing = false,
            revision = revision) &&
        nowMs >= pending.notBeforeMs

    /** A finite fallback when the native list is still settling or has no laid-out children.
     * Layout/scroll/idle hooks may all arrive together; only one callback is queued at a time. */
    fun queueTransientRecheck(pending: AppleLyricsFollowRecheck, nowMs: Long): Long? {
        if (nowMs < pending.notBeforeMs ||
            nowMs - pending.notBeforeMs > MAX_TRANSIENT_RECHECK_WINDOW_MS ||
            pending.transientRecheckQueued ||
            pending.transientRecheckCount >= TRANSIENT_RECHECK_DELAYS_MS.size
        ) return null
        val delay = TRANSIENT_RECHECK_DELAYS_MS[pending.transientRecheckCount]
        pending.transientRecheckCount++
        pending.transientRecheckQueued = true
        return delay
    }

    fun onTransientRecheckDelivered(pending: AppleLyricsFollowRecheck) {
        pending.transientRecheckQueued = false
    }

    /** After a successful reflected method invocation, wait for the host to
     * lay out the target row instead of equating invocation with visibility. */
    fun noteScrollDispatched(
        pending: AppleLyricsFollowRecheck, targetPosition: Int, nowMs: Long,
    ) {
        pending.dispatchedTargetPosition = targetPosition
        pending.dispatchedAtMs = nowMs
        pending.dispatchConfirmationQueued = false
        pending.dispatchConfirmationDelivered = false
        pending.dispatchUnconfirmedReported = false
    }

    /** An acknowledged native invocation stays a single experiment until
     * this active-row owner is replaced; delayed observations must not chase. */
    fun mayDispatchFollow(pending: AppleLyricsFollowRecheck): Boolean =
        pending.dispatchedTargetPosition == null

    fun queueDispatchConfirmation(pending: AppleLyricsFollowRecheck): Long? {
        if (pending.dispatchedTargetPosition == null ||
            pending.dispatchConfirmationQueued || pending.dispatchConfirmationDelivered
        ) return null
        pending.dispatchConfirmationQueued = true
        return DISPATCH_CONFIRMATION_MS
    }

    fun onDispatchConfirmationDelivered(pending: AppleLyricsFollowRecheck) {
        pending.dispatchConfirmationQueued = false
        pending.dispatchConfirmationDelivered = true
    }

    /** Called only after the host is idle with measured rows and the bound
     * native active row is still offscreen. Report once, never auto-chase. */
    fun markUnconfirmedIfReady(pending: AppleLyricsFollowRecheck): Boolean {
        if (!pending.dispatchConfirmationDelivered || pending.dispatchUnconfirmedReported ||
            pending.dispatchedTargetPosition == null
        ) return false
        pending.dispatchUnconfirmedReported = true
        return true
    }
}

/** Clock overlap can advance with no new active-set, layout, or scroll callback.
 * Visual blur is deliberately irrelevant to follow observation. */
internal object AppleLyricsOverlapFocusPollPolicy {
    fun needsVisualRefresh(
        blurEnabled: Boolean, nativeActiveChanged: Boolean, focusChanged: Boolean,
    ): Boolean = blurEnabled && (nativeActiveChanged || focusChanged)

    @Suppress("UNUSED_PARAMETER") // Passing the preference guards accidental future blur coupling.
    fun nextDelayMs(
        nativeActive: Set<Int>, playbackAdvancing: Boolean,
        userBrowsing: Boolean, attached: Boolean, visible: Boolean,
        blurEnabled: Boolean,
    ): Long? {
        if (!attached || userBrowsing || nativeActive.size < 2) return null
        // A frozen player or background/hidden lyric page needs only a slow
        // wake-up. Resume/show must still recover without an adapter callback.
        return if (playbackAdvancing && visible) 96L else 1_000L
    }
}

/** `onScrolled` and SETTLING also occur during Apple's own auto-follow.
 * Only an explicit DRAGGING state proves that the listener is browsing. */
internal object AppleLyricsScrollIntentPolicy {
    private const val SCROLL_STATE_DRAGGING = 1

    /** Keep adapter ownership current even when active-line callbacks arrive
     * before the next async blur frame. That frame must not erase the pending
     * follow check for the adapter that has already become current. */
    fun adoptAdapter(
        state: AppleLyricsBlurRuntimeState, adapter: Any, songId: String? = null,
        revision: Long? = null,
    ) {
        val adapterChanged = state.adapterRef?.get() !== adapter
        val knownSong = songId?.takeIf(String::isNotBlank)
        val songChanged = knownSong != null && state.presentationSongId != knownSong
        val knownRevision = revision?.takeIf { it >= 0L }
        val revisionChanged = knownSong != null && knownRevision != null &&
            state.presentationRevision != knownRevision
        if (!adapterChanged && !songChanged && !revisionChanged) return
        state.adapterRef = WeakReference(adapter)
        state.presentationSongId = if (adapterChanged) knownSong
            else knownSong ?: state.presentationSongId
        state.presentationRevision = if (adapterChanged || songChanged) knownRevision
            else knownRevision ?: state.presentationRevision
        state.settledAnchorTopY = null
        // Updating lyrics in the same song is not permission to override a
        // listener's explicit DRAGGING intent. Only a new song/adapter owns a
        // fresh automatic-follow session.
        if (adapterChanged || songChanged) state.suspendedForScroll = false
        state.motionBlurCleared = false
        state.pendingProgrammaticRecenterPosition = null
        state.lastActivePositions = emptySet()
        state.lastFocusedActivePosition = null
        state.lastFollowRecoveryAtMs = 0L
        state.pendingNativeFollowCheck = null
        state.pendingOutgoingPositions = emptySet()
        state.outgoingZoneTopByPosition = emptyMap()
    }

    /** Both blur frames and active-line callbacks observe the same native
     * presentation. Whichever arrives first must establish its pending follow
     * owner; a repeated observation must not replace the live timer. */
    fun observeNativeActiveForCurrentAdapter(
        state: AppleLyricsBlurRuntimeState,
        callbackAdapter: Any,
        renderedAdapter: Any?,
        songId: String?,
        revision: Long,
        activePositions: Set<Int>?,
        nowMs: Long,
    ): Boolean {
        // RecyclerView already replaced this adapter. Calling the ordinary
        // observer would re-adopt the retired adapter and clear a real drag.
        if (renderedAdapter !== callbackAdapter) return false
        return observeNativeActive(
            state, callbackAdapter, songId, revision, activePositions, nowMs,
        )
    }

    fun observeNativeActive(
        state: AppleLyricsBlurRuntimeState,
        adapter: Any,
        songId: String?,
        revision: Long,
        activePositions: Set<Int>?,
        nowMs: Long,
    ): Boolean {
        adoptAdapter(state, adapter, songId, revision)
        // A failed native reflection is not an observation of zero active rows.
        if (activePositions == null) return false
        val changed = updateActivePositions(state, activePositions)
        if (changed) {
            state.pendingNativeFollowCheck = if (state.suspendedForScroll) null
            else AppleLyricsFollowRecheckPolicy.begin(
                adapter, songId, activePositions, nowMs, revision = revision,
            )
        }
        return changed
    }

    /** Apple may keep two overlapping rows in the same active set while its
     * *verified* playback focus advances from the visible one to an offscreen
     * one. Focus movement must also create a follow owner; otherwise the
     * earlier settled check has already been discarded and nothing retries. */
    fun observeVerifiedNativeFocus(
        state: AppleLyricsBlurRuntimeState,
        adapter: Any,
        songId: String?,
        revision: Long,
        activePositions: Set<Int>,
        focusedPosition: Int?,
        nowMs: Long,
    ): Boolean {
        if (focusedPosition == null || focusedPosition !in activePositions ||
            state.adapterRef?.get() !== adapter || songId.isNullOrBlank() ||
            state.presentationSongId != songId || state.presentationRevision != revision ||
            state.lastActivePositions != activePositions ||
            state.lastFocusedActivePosition == focusedPosition
        ) return false
        state.lastFocusedActivePosition = focusedPosition
        if (state.suspendedForScroll) {
            state.pendingNativeFollowCheck = null
            return false
        }
        state.pendingNativeFollowCheck = AppleLyricsFollowRecheckPolicy.begin(
            adapter, songId, activePositions, nowMs, revision = revision,
        )
        return state.pendingNativeFollowCheck != null
    }

    private fun updateActivePositions(
        state: AppleLyricsBlurRuntimeState,
        activePositions: Set<Int>,
    ): Boolean {
        val previousPositions = state.lastActivePositions
        val pending = state.pendingProgrammaticRecenterPosition
        if (!state.suspendedForScroll && pending != null && pending !in activePositions) {
            state.pendingProgrammaticRecenterPosition = null
        }
        if (state.suspendedForScroll) {
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            state.lastActivePositions = activePositions
            return previousPositions != activePositions
        }
        val newlyOutgoingPositions = previousPositions - activePositions
        val pendingOutgoingPositions =
            (state.pendingOutgoingPositions + newlyOutgoingPositions) - activePositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition
            .filterKeys { it in pendingOutgoingPositions }
            .toMutableMap()
        state.settledAnchorTopY?.let { currentZoneTopY ->
            newlyOutgoingPositions.forEach { position ->
                outgoingZoneTopByPosition.putIfAbsent(position, currentZoneTopY)
            }
        }
        state.pendingOutgoingPositions = pendingOutgoingPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
        state.lastActivePositions = activePositions
        return previousPositions != activePositions
    }

    /** True once per motion period when existing native blur needs clearing. */
    fun recordMovement(state: AppleLyricsBlurRuntimeState, scrollState: Int): Boolean {
        if (scrollState == SCROLL_STATE_DRAGGING) {
            state.suspendedForScroll = true
            state.pendingProgrammaticRecenterPosition = null
            state.pendingNativeFollowCheck = null
        }
        if (state.motionBlurCleared) return false
        state.motionBlurCleared = true
        return true
    }

    fun onViewportHeightChanged(state: AppleLyricsBlurRuntimeState, height: Int) {
        if (height <= 0 || height == state.recyclerHeight) return
        state.recyclerHeight = height
        state.settledAnchorTopY = null
        state.motionBlurCleared = false
        state.pendingOutgoingPositions = emptySet()
        state.outgoingZoneTopByPosition = emptyMap()
    }

    /** Mark intent before invoking the host so its synchronous scroll event
     * is recognized as programmatic. Intent alone is not a successful call. */
    fun requestRecovery(state: AppleLyricsBlurRuntimeState, target: Int) {
        state.pendingProgrammaticRecenterPosition = target
    }

    /** A missing/throwing reflected method must not claim the four-second
     * cooldown, or the same stuck lyric can remain offscreen indefinitely. */
    fun finishRecoveryDispatch(
        state: AppleLyricsBlurRuntimeState,
        owner: AppleLyricsFollowRecheck,
        target: Int,
        nowMs: Long,
        dispatched: Boolean,
    ): Boolean {
        // A host method can reenter active-line hooks before returning.
        // Never apply its result to a newer song/adapter/row recovery owner.
        if (state.pendingNativeFollowCheck !== owner) return false
        if (dispatched) {
            state.lastFollowRecoveryAtMs = nowMs
        } else if (state.pendingProgrammaticRecenterPosition == target) {
            state.pendingProgrammaticRecenterPosition = null
        }
        return true
    }

    /** Idle clears only motion blur; user browsing still blocks forced recovery. */
    fun endMotion(state: AppleLyricsBlurRuntimeState) {
        state.motionBlurCleared = false
    }
}
