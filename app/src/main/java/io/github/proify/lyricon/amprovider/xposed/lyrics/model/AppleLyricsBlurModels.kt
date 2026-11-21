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
    /** Persistent user browsing / pending host recenter, not all native motion. */
    var suspendedForScroll: Boolean = false,
    /** Clear blur once per motion period without mistaking auto-follow for browsing. */
    var motionBlurCleared: Boolean = false,
    var pendingProgrammaticRecenterPosition: Int? = null,
    var lastActivePositions: Set<Int> = emptySet(),
    var lastFocusedActivePosition: Int? = null,
    var lastFollowRecoveryAtMs: Long = 0L,
    var pendingOutgoingPositions: Set<Int> = emptySet(),
    var outgoingZoneTopByPosition: Map<Int, Float> = emptyMap(),
    var pendingApplyBlur: Runnable? = null,
    var lastDiagnosticSignature: String? = null,
    var blurMode: Int? = null,
    var currentBlurRadius: Float = 0f,
    var targetBlurRadius: Float = 0f,
    var blurAnimator: ValueAnimator? = null,
)

/** `onScrolled` and SETTLING also occur during Apple's own auto-follow.
 * Only an explicit DRAGGING state proves that the listener is browsing. */
internal object AppleLyricsScrollIntentPolicy {
    private const val SCROLL_STATE_DRAGGING = 1

    /** True once per motion period when existing native blur needs clearing. */
    fun recordMovement(state: AppleLyricsBlurRuntimeState, scrollState: Int): Boolean {
        if (scrollState == SCROLL_STATE_DRAGGING) {
            state.suspendedForScroll = true
            state.pendingProgrammaticRecenterPosition = null
        }
        if (state.motionBlurCleared) return false
        state.motionBlurCleared = true
        return true
    }

    /** Idle clears only motion blur; user browsing still blocks forced recovery. */
    fun endMotion(state: AppleLyricsBlurRuntimeState) {
        state.motionBlurCleared = false
    }
}
