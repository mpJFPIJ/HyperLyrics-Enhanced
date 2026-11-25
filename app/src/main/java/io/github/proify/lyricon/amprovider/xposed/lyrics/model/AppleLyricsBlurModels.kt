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
)

/** Track the exact adapter and active row across native layout/idle events.
 * A delayed callback alone can miss the row while Apple's RecyclerView settles. */
internal object AppleLyricsFollowRecheckPolicy {
    const val NATIVE_SETTLE_MS = 750L

    fun begin(
        adapter: Any, songId: String?, active: Set<Int>, nowMs: Long,
    ): AppleLyricsFollowRecheck? {
        if (active.isEmpty() || songId.isNullOrBlank()) return null
        return AppleLyricsFollowRecheck(
            WeakReference(adapter), songId, active.toSet(), nowMs + NATIVE_SETTLE_MS,
        )
    }

    fun isStale(
        pending: AppleLyricsFollowRecheck,
        adapter: Any?,
        songId: String?,
        active: Set<Int>,
        userBrowsing: Boolean,
    ): Boolean = userBrowsing || songId != pending.songId || adapter == null ||
        pending.adapterRef.get() !== adapter || pending.active != active

    fun shouldEvaluate(
        pending: AppleLyricsFollowRecheck,
        adapter: Any?,
        songId: String?,
        active: Set<Int>,
        nowMs: Long,
    ): Boolean = !isStale(pending, adapter, songId, active, userBrowsing = false) &&
        nowMs >= pending.notBeforeMs
}

/** `onScrolled` and SETTLING also occur during Apple's own auto-follow.
 * Only an explicit DRAGGING state proves that the listener is browsing. */
internal object AppleLyricsScrollIntentPolicy {
    private const val SCROLL_STATE_DRAGGING = 1

    /** Keep adapter ownership current even when active-line callbacks arrive
     * before the next async blur frame. That frame must not erase the pending
     * follow check for the adapter that has already become current. */
    fun adoptAdapter(state: AppleLyricsBlurRuntimeState, adapter: Any) {
        if (state.adapterRef?.get() === adapter) return
        state.adapterRef = WeakReference(adapter)
        state.settledAnchorTopY = null
        state.suspendedForScroll = false
        state.motionBlurCleared = false
        state.pendingProgrammaticRecenterPosition = null
        state.lastActivePositions = emptySet()
        state.lastFocusedActivePosition = null
        state.lastFollowRecoveryAtMs = 0L
        state.pendingNativeFollowCheck = null
        state.pendingOutgoingPositions = emptySet()
        state.outgoingZoneTopByPosition = emptyMap()
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

    fun requestRecovery(state: AppleLyricsBlurRuntimeState, target: Int, nowMs: Long) {
        state.pendingProgrammaticRecenterPosition = target
        state.lastFollowRecoveryAtMs = nowMs
    }

    /** Idle clears only motion blur; user browsing still blocks forced recovery. */
    fun endMotion(state: AppleLyricsBlurRuntimeState) {
        state.motionBlurCleared = false
    }
}
