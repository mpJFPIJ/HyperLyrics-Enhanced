/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleDebugNetworkHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.lyrics.AppleOnlineSourceMenuHooks
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt


internal class AppleLyricsBlurHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> android.content.SharedPreferences?,
    internal val playbackHooks: () -> ApplePlaybackHooks,
    private val currentFragment: () -> Any?,
    private val currentSongId: () -> String?,
    private val currentRevision: () -> Long,
) {
    internal var onLyricsUserDrag: ((View) -> Unit)? = null

    private val verifiedNativeWindows = AppleLyricsTimedAdapterWindowIndex()

    internal fun rememberVerifiedNativeWindow(
        songId: String?, revision: Long, adapterOwner: Any?,
        adapterPosition: Int?, beginMs: Long, endMs: Long,
    ) {
        if (adapterPosition != null) verifiedNativeWindows.remember(
            songId, revision, adapterOwner, adapterPosition, beginMs, endMs,
        )
    }

    /** Restore may use only a host-bound row↔clock mapping corroborated by
     * Apple's current active set. Logical lyric indexes are not adapter rows. */
    internal fun verifiedAdapterPositionForPlayback(
        adapter: Any,
        playbackPositionMs: Long,
        nativeActive: Set<Int> = appleLyricsActiveAdapterPositions(adapter),
    ): Int? =
        verifiedNativeWindows.focus(
            currentSongId(), currentRevision(), adapter,
            nativeActive, playbackPositionMs,
        )

    /** Adapter positions are sourced from Apple's host classloader. When two
     * outgoing/incoming rows are both marked active, use the genuine holder
     * binding windows; unknown focus never promotes a native reading. */
    internal fun currentNativeFocusedAdapterPosition(
        adapter: Any,
        active: Set<Int> = appleLyricsActiveAdapterPositions(adapter),
        playbackPositionMs: Long? = appleLyricsCurrentPlaybackPositionMs(),
    ): Int? {
        return when (active.size) {
            1 -> active.first().takeUnless { row ->
                verifiedNativeWindows.matchesKnownWindow(
                    currentSongId(), currentRevision(), adapter, row, playbackPositionMs,
                ) == false
            }
            0 -> null
            else -> verifiedNativeWindows.focus(
                currentSongId(), currentRevision(), adapter, active, playbackPositionMs,
            )
        }
    }
    internal companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION = 0.22f
        const val APPLE_LYRICS_SCROLL_STATE_IDLE = 0
        const val APPLE_LYRICS_IDLE_RECHECK_DELAY_MS = 96L
        const val APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS = 16L
        const val APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS = 250L
        const val APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE = 0
        const val APPLE_LYRICS_BLUR_ANIMATION_DURATION_MS = 300L
    }

    private val application: Application
        get() = runtime.application
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val hookRegistrar
        get() = runtime.hookRegistrar
    private val mainHandler: Handler
        get() = runtime.mainHandler
    private val contentUiLanguagePrefs: android.content.SharedPreferences?
        get() = preferences()

    /** 滚动态与去重集合的持有者；Hook 发现/安装/事件转交仍由本类负责。 */
    internal val blurState = AppleLyricsBlurState()
    private val appleLyricsRecyclerAdapterClassNames by lazy {
        hookResolver.configuredClassNames(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).toSet()
    }
    private val lyricsRecyclerAdapterTargets by lazy {
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
    }
    private val lyricsUiTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).target
    }
    private val lyricsNativeTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target
    }

    internal fun lyricsAdapterMember(
        adapter: Any,
        member: AppleMusicRuntimeMember,
    ): String = lyricsRecyclerAdapterTargets
        .firstOrNull { resolved -> resolved.clazz.isAssignableFrom(adapter.javaClass) }
        ?.target
        ?.runtimeMemberName(member)
        ?: error("Apple Music lyrics adapter target unavailable: ${adapter.javaClass.name}")

    internal fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    internal fun lyricsNativeMember(member: AppleMusicRuntimeMember): String =
        lyricsNativeTarget.runtimeMemberName(member)


    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        adapter?.javaClass?.name in appleLyricsRecyclerAdapterClassNames

    private fun appleLyricsBlurMode(): Int {
        val prefs = contentUiLanguagePrefs
        val configured = prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT
        return AppleLyricsBlurPolicy.normalizeMode(configured)
    }
    internal fun appleLyricsBlurAnimationEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs
        return prefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION
    }

    private fun appleLyricsBlurRadiusRange(mode: Int): ClosedFloatingPointRange<Float> {
        val prefs = contentUiLanguagePrefs
        val (configuredMin, configuredMax, allowedMin, allowedMax) =
            if (mode == AppleLyricsBlurPolicy.NATIVE) {
                listOf(
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                    RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                )
            } else {
                listOf(
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX)
                        .toFloat(),
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX)
                        .toFloat(),
                    RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                    RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                )
            }
        val boundedMin = configuredMin.coerceIn(allowedMin, allowedMax)
        val boundedMax = configuredMax.coerceIn(allowedMin, allowedMax)
        return minOf(boundedMin, boundedMax)..maxOf(boundedMin, boundedMax)
    }


    fun refreshAppleLyricsBlurEffect() {
        mainHandler.post {
            val fragment = currentFragment() ?: return@post
            scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
        }
    }

    fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) {
        val recyclerViewAsView = recyclerView as? View ?: return
        // 歌词自动滚动时 onScrolled/onLayout 几乎每帧触发：每个 RecyclerView 复用同一个
        // Runnable，先撤回已排队的再重新投递，不再每帧新建 Runnable 与 WeakReference。
        val applyBlur = blurState.withRuntimeState(recyclerViewAsView) { state ->
            state.pendingApplyBlur
                ?: createAppleLyricsBlurRunnable(recyclerViewAsView)
                    .also { state.pendingApplyBlur = it }
        }
        recyclerViewAsView.removeCallbacks(applyBlur)
        if (delayMs > 0L) {
            recyclerViewAsView.postDelayed(applyBlur, delayMs)
        } else {
            recyclerViewAsView.postOnAnimation(applyBlur)
        }
    }

    /** 只在仍是该视图当前登记的 Runnable 时执行；状态被重置后旧 Runnable 自动失效。 */
    private fun createAppleLyricsBlurRunnable(recyclerView: View): Runnable {
        val viewRef = WeakReference(recyclerView)
        return object : Runnable {
            override fun run() {
                val target = viewRef.get() ?: return
                val isCurrent = blurState.withExistingRuntimeState(target) { state ->
                    state.pendingApplyBlur === this
                } == true
                if (!isCurrent) return
                applyAppleLyricsBlur(target)
            }
        }
    }

    /** The next clock boundary can occur inside an unchanged native {row A,row B}
     * active set. Keep follow observation independent from blur frame rendering. */
    private fun updateAppleLyricsOverlapFocusPoll(
        recyclerView: View, state: AppleLyricsBlurRuntimeState, active: Set<Int>?,
    ) {
        val delay = AppleLyricsOverlapFocusPollPolicy.nextDelayMs(
            nativeActive = active ?: state.lastActivePositions,
            // BUFFERING keeps the playback session active but freezes Exo's
            // measured timeline, just like PAUSED for focus polling.
            playbackAdvancing = playbackHooks().isTimelineAdvancing(),
            userBrowsing = state.suspendedForScroll,
            attached = recyclerView.isAttachedToWindow,
            visible = recyclerView.isShown,
            blurEnabled = appleLyricsBlurMode() != AppleLyricsBlurPolicy.OFF,
        )
        val pending = state.pendingOverlapFocusPoll
        pending?.let(recyclerView::removeCallbacks)
        if (delay == null) return
        val runnable = pending ?: createAppleLyricsOverlapFocusPoll(recyclerView)
            .also { state.pendingOverlapFocusPoll = it }
        recyclerView.postDelayed(runnable, delay)
    }

    private fun createAppleLyricsOverlapFocusPoll(recyclerView: View): Runnable {
        val viewRef = WeakReference(recyclerView)
        return object : Runnable {
            override fun run() {
                val view = viewRef.get() ?: return
                val state = blurState.withExistingRuntimeState(view) { current ->
                    current.takeIf { it.pendingOverlapFocusPoll === this }
                } ?: return
                if (!view.isAttachedToWindow) return
                val adapter = appleRecyclerAdapter(view) ?: return
                val songId = currentSongId()
                val revision = currentRevision()
                val active = appleLyricsNativeActiveAdapterPositionsOrNull(adapter)
                val now = SystemClock.elapsedRealtime()
                val activeChanged = AppleLyricsScrollIntentPolicy.observeNativeActive(
                    state, adapter, songId, revision, active, now,
                )
                val clockFocus = if (active != null && active.size > 1)
                    currentNativeFocusedAdapterPosition(adapter, active)
                else null
                val focusChanged = active != null &&
                    AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
                        state, adapter, songId, revision, active, clockFocus, now,
                    )
                if ((activeChanged || focusChanged) && !active.isNullOrEmpty()) {
                    scheduleAppleLyricsNativeFollowAfterSettle(view)
                }
                if (AppleLyricsOverlapFocusPollPolicy.needsVisualRefresh(
                        blurEnabled = appleLyricsBlurMode() != AppleLyricsBlurPolicy.OFF,
                        nativeActiveChanged = activeChanged,
                        focusChanged = focusChanged,
                    )
                ) {
                    // There need not be any more host layout or active-set
                    // callbacks during a constant two-row overlap. Repaint
                    // blur only at verified focus transitions, not each poll.
                    scheduleAppleLyricsBlur(view)
                }
                updateAppleLyricsOverlapFocusPoll(view, state, active)
            }
        }
    }

    private fun resetAppleLyricsBlurRuntimeState(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val previous = blurState.removeRuntimeState(recyclerViewAsView)
        previous?.pendingApplyBlur?.let(recyclerViewAsView::removeCallbacks)
        previous?.pendingOverlapFocusPoll?.let(recyclerViewAsView::removeCallbacks)
        clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        scheduleAppleLyricsBlur(recyclerViewAsView)
    }

    private fun recordAppleLyricsScrollMotion(recyclerView: Any?, scrollState: Int) {
        val recyclerViewAsView = recyclerView as? View ?: return
        if (scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
            onLyricsUserDrag?.invoke(recyclerViewAsView)
        }
        var beganUserBrowse = false
        val shouldClearBlur = blurState.withRuntimeState(recyclerViewAsView) { state ->
            val previouslyBrowsing = state.suspendedForScroll
            val clear = AppleLyricsScrollIntentPolicy.recordMovement(state, scrollState)
            beganUserBrowse = !previouslyBrowsing && state.suspendedForScroll
            clear
        }
        if (beganUserBrowse && BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] user_browse_started: recycler=${System.identityHashCode(recyclerViewAsView)}"
            )
        }
        if (shouldClearBlur) {
            clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        }
    }

    private fun onAppleLyricsProgrammaticRecenterRequested(
        layoutManager: Any?,
        targetPosition: Int,
    ) {
        if (layoutManager == null || targetPosition < 0) return
        val recyclerView = blurState.firstRuntimeStateKey { candidate ->
            runCatching {
                AppleReflection.call(candidate, "getLayoutManager")
            }.getOrNull() === layoutManager
        } ?: return
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            return
        }
        val marked = blurState.withRuntimeState(recyclerView) { state ->
            if (!state.suspendedForScroll) {
                false
            } else {
                state.pendingProgrammaticRecenterPosition = targetPosition
                true
            }
        }
        if (marked && BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple lyrics blur: programmatic_recenter_requested, " +
                    "target=$targetPosition"
            )
        }
    }

    private fun completeAppleLyricsProgrammaticRecenter(recyclerView: Any?): Boolean {
        val recyclerViewAsView = recyclerView as? View ?: return false
        val targetPosition = blurState.runtimeStateOrNull(recyclerViewAsView)
            ?.pendingProgrammaticRecenterPosition ?: return false
        val adapter = appleRecyclerAdapter(recyclerViewAsView) ?: return false
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val positionedChildren = appleLyricsOnScreenChildren(recyclerViewAsView)
        val visiblePositions = positionedChildren.mapTo(mutableSetOf()) { it.first }
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val scrollState = appleLyricsRecyclerScrollState(recyclerViewAsView)
        if (!shouldCompleteAppleLyricsProgrammaticRecenter(
                scrollState, targetPosition, focusPositions, visiblePositions,
            )
        ) return false
        var wasBrowsing = false
        val completed = blurState.withExistingRuntimeState(recyclerViewAsView) { state ->
            if (state.pendingProgrammaticRecenterPosition != targetPosition) {
                return@withExistingRuntimeState false
            }
            wasBrowsing = state.suspendedForScroll
            state.suspendedForScroll = false
            state.pendingProgrammaticRecenterPosition = null
            true
        } == true
        if (completed) {
            if (!wasBrowsing) {
                AppleLyricUiRuntimeProbe.record(
                    "native_follow_recovered",
                    "target=$targetPosition visible=${visiblePositions.sorted()}",
                )
            }
            scheduleAppleLyricsBlur(recyclerViewAsView)
            ProviderLogger.debug(
                "Apple Music 歌词滚动恢复完成: " +
                    "target=$targetPosition, " +
                    "positioned=${positionedChildren.map { it.first }.sorted()}, " +
                    "active=${activePositions.sorted()}, " +
                    "instrumental=${instrumentalPositions.sorted()}, " +
                    "writers=${writersCreditsPositions.sorted()}, " +
                    "focus=${focusPositions.sorted()}"
            )
        }
        return completed
    }

    private fun onAppleLyricsScrollStateChanged(
        recyclerView: Any?,
        scrollState: Int,
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] onAppleLyricsScrollStateChanged: scrollState=$scrollState (idle=${scrollState == APPLE_LYRICS_SCROLL_STATE_IDLE})"
            )
        }
        if (scrollState != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            recordAppleLyricsScrollMotion(recyclerView, scrollState)
            return
        }
        (recyclerView as? View)?.let { view ->
            blurState.withRuntimeState(view, AppleLyricsScrollIntentPolicy::endMotion)
            completeAppleLyricsProgrammaticRecenter(view)
            maybeRecoverAppleLyricsNativeFollow(view)
        }
        scheduleAppleLyricsBlur(
            recyclerView = recyclerView,
            delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
        )
    }

    private fun onAppleLyricsActiveLinesUpdated(adapter: Any?) {
        adapter ?: return
        val recyclerView = blurState.recyclerFor(adapter) ?: return
        val renderedAdapter = appleRecyclerAdapter(recyclerView)
        if (renderedAdapter !== adapter) return
        // A missing reflected result cannot safely erase a previously
        // observed native active set or cancel its pending follow check.
        val activePositions = appleLyricsNativeActiveAdapterPositionsOrNull(adapter)
            ?: return
        val observedSongId = currentSongId()
        val observedRevision = currentRevision()
        val verifiedFocus = if (activePositions.size > 1)
            currentNativeFocusedAdapterPosition(adapter, activePositions)
        else null
        val changed = blurState.withRuntimeState(recyclerView) { state ->
            val activeChanged = AppleLyricsScrollIntentPolicy.observeNativeActiveForCurrentAdapter(
                state, adapter, renderedAdapter,
                observedSongId, observedRevision, activePositions,
                SystemClock.elapsedRealtime(),
            )
            val focusChanged = AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
                state, adapter, observedSongId, observedRevision,
                activePositions, verifiedFocus, SystemClock.elapsedRealtime(),
            )
            activeChanged || focusChanged
        }
        if (changed) {
            scheduleAppleLyricsBlur(recyclerView)
            if (activePositions.isNotEmpty()) {
                // Give the host's normal auto-follow time to settle. A stuck
                // idle native list may be recentered once, but never while the
                // listener is browsing. An unsettled/empty sample gets only
                // bounded retries when layout and scroll callbacks stop.
                scheduleAppleLyricsNativeFollowAfterSettle(recyclerView)
            }
        }
        blurState.runtimeStateOrNull(recyclerView)?.let { state ->
            updateAppleLyricsOverlapFocusPoll(recyclerView, state, activePositions)
        }
    }

    private fun scheduleAppleLyricsNativeFollowAfterSettle(recyclerView: View) {
        recyclerView.postDelayed(
            { maybeRecoverAppleLyricsNativeFollow(recyclerView) },
            AppleLyricsFollowRecheckPolicy.NATIVE_SETTLE_MS,
        )
    }

    private fun scheduleAppleLyricsTransientFollowRecheck(
        recyclerView: View,
        pending: AppleLyricsFollowRecheck,
        nowMs: Long,
    ) {
        val delay = AppleLyricsFollowRecheckPolicy.queueTransientRecheck(pending, nowMs) ?: return
        recyclerView.postDelayed({
            AppleLyricsFollowRecheckPolicy.onTransientRecheckDelivered(pending)
            // An adapter change, song change, active-line change or user drag
            // replaces/cancels this instance. Never replay a retired check.
            if (blurState.runtimeStateOrNull(recyclerView)?.pendingNativeFollowCheck === pending) {
                maybeRecoverAppleLyricsNativeFollow(recyclerView)
            }
        }, delay)
    }

    private fun scheduleAppleLyricsDispatchedFollowConfirmation(
        recyclerView: View,
        pending: AppleLyricsFollowRecheck,
    ) {
        val delay = AppleLyricsFollowRecheckPolicy.queueDispatchConfirmation(pending) ?: return
        recyclerView.postDelayed({
            AppleLyricsFollowRecheckPolicy.onDispatchConfirmationDelivered(pending)
            if (blurState.runtimeStateOrNull(recyclerView)?.pendingNativeFollowCheck === pending) {
                // Verify current native visibility after the host has had a
                // frame to lay out its acknowledged scroll request.
                maybeRecoverAppleLyricsNativeFollow(recyclerView)
            }
        }, delay)
    }

    private fun maybeRecoverAppleLyricsNativeFollow(recyclerView: View) {
        val state = blurState.runtimeStateOrNull(recyclerView) ?: return
        val pending = state.pendingNativeFollowCheck ?: return
        if (!recyclerView.isAttachedToWindow) return
        val adapter = appleRecyclerAdapter(recyclerView)
        val songId = currentSongId()
        val revision = currentRevision()
        val active = adapter?.let { appleLyricsNativeActiveAdapterPositionsOrNull(it) }
        if (AppleLyricsFollowRecheckPolicy.isStale(
                pending, adapter, songId, active,
                userBrowsing = state.suspendedForScroll,
                revision = revision,
            )
        ) {
            if (state.pendingNativeFollowCheck === pending) {
                state.pendingNativeFollowCheck = null
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (active == null) {
            // Preserve a live pending owner through transient reflection
            // failure; never mistake it for Apple's observed empty set.
            scheduleAppleLyricsTransientFollowRecheck(recyclerView, pending, now)
            return
        }
        if (!AppleLyricsFollowRecheckPolicy.shouldEvaluate(
                pending, adapter, songId, active, now,
                revision = revision,
            )
        ) return
        val scrollState = appleLyricsRecyclerScrollState(recyclerView)
        // Native settling can finish without a further layout or IDLE callback.
        if (scrollState != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            scheduleAppleLyricsTransientFollowRecheck(recyclerView, pending, now)
            return
        }
        val visible = appleLyricsOnScreenChildren(recyclerView)
            .mapTo(mutableSetOf()) { it.first }
        if (visible.isEmpty()) {
            scheduleAppleLyricsTransientFollowRecheck(recyclerView, pending, now)
            return
        }
        // Native overlapping active rows are not ordered by playback time.
        // Reuse the *same* sampled set to avoid a second adapter reflection;
        // Apple's observed binding windows decide which one the clock owns.
        val trustedFocus = adapter?.let { currentNativeFocusedAdapterPosition(it, active) }
        val targetPosition = AppleLyricsFollowRecheckPolicy.trustedTargetPosition(
            active, trustedFocus,
        )
        if (AppleLyricsFollowRecheckPolicy.hasVisibleOwnedRow(
                active, visible, targetPosition, pending.dispatchedTargetPosition,
            )) {
            if (pending.dispatchedTargetPosition != null) {
                AppleLyricUiRuntimeProbe.record(
                    "native_follow_dispatch_visible",
                    "position=${pending.dispatchedTargetPosition} " +
                        "duration_ms=${(now - pending.dispatchedAtMs).coerceAtLeast(0L)} " +
                        "activeCount=${active.size} visibleCount=${visible.size}",
                    recyclerView,
                )
            }
            state.pendingNativeFollowCheck = null
            return
        }
        if (AppleLyricsFollowRecheckPolicy.markUnconfirmedIfReady(pending)) {
            AppleLyricUiRuntimeProbe.record(
                "native_follow_dispatch_unconfirmed",
                "position=${pending.dispatchedTargetPosition ?: -1} " +
                    "duration_ms=${(now - pending.dispatchedAtMs).coerceAtLeast(0L)} " +
                    "activeCount=${active.size} visibleCount=${visible.size}",
                recyclerView,
            )
        }
        AppleLyricUiRuntimeProbe.record(
            "native_follow_offscreen_candidate",
            "active=${active.sorted()} visible=${visible.sorted()} " +
                "scrollState=$scrollState playbackMs=${appleLyricsCurrentPlaybackPositionMs() ?: -1}",
        )
        // A successful native method invocation is still waiting for the
        // host's visual result. Do not force another recenter on this same
        // row merely because the cooldown expired during later callbacks.
        if (!AppleLyricsFollowRecheckPolicy.mayDispatchFollow(pending)) return
        // Unknown focus during native overlap is not a license to scroll to
        // the greatest adapter position (which can still be the future row).
        if (targetPosition == null) return
        val eligible = AppleLyricsFollowRecoveryPolicy.shouldRecover(
            active = active,
            visible = visible,
            scrollState = scrollState,
            suspendedForBrowse = state.suspendedForScroll,
            playing = playbackHooks().isPlaying(),
            nowMs = now,
            lastRecoveryAtMs = state.lastFollowRecoveryAtMs,
            focusTarget = targetPosition,
        )
        if (!eligible) return
        val manager = runCatching {
            AppleReflection.call(recyclerView, "getLayoutManager")
        }.getOrNull() ?: run {
            // The adapter can be ready before its host LayoutManager is.
            // An otherwise idle list is not guaranteed another layout event.
            scheduleAppleLyricsTransientFollowRecheck(recyclerView, pending, now)
            return
        }
        AppleLyricsScrollIntentPolicy.requestRecovery(state, targetPosition)
        val method = appleLyricsScrollToPositionWithOffset(
            layoutManager = manager,
            position = targetPosition,
            offset = (recyclerView.height *
                APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION).roundToInt(),
        )
        if (!AppleLyricsScrollIntentPolicy.finishRecoveryDispatch(
                state, owner = pending, target = targetPosition,
                nowMs = now, dispatched = method != null,
            )) return
        if (method == null) {
            // Apple may temporarily expose no compatible scroll method while
            // the lyrics LayoutManager swaps. Retry this same live song/row
            // a bounded number of times, without a false four-second block.
            scheduleAppleLyricsTransientFollowRecheck(recyclerView, pending, now)
        } else {
            AppleLyricsFollowRecheckPolicy.noteScrollDispatched(
                pending, targetPosition, now,
            )
            scheduleAppleLyricsDispatchedFollowConfirmation(recyclerView, pending)
            AppleLyricUiRuntimeProbe.record(
                "native_follow_recovery_requested",
                "target=$targetPosition method=$method playbackMs=" +
                    "${appleLyricsCurrentPlaybackPositionMs() ?: -1}",
            )
        }
    }

    /** Register the lyric list even with the blur effect disabled. */
    private fun rememberAppleLyricsRecyclerAdapter(recyclerView: Any?) {
        val view = recyclerView as? View ?: return
        val adapter = appleRecyclerAdapter(view) ?: return
        blurState.rememberRecycler(adapter, view)
    }

    private fun applyAppleLyricsBlur(recyclerView: View) {
        val container = recyclerView as? ViewGroup ?: return
        val state = blurState.withRuntimeState(recyclerView) { it }
        val mode = appleLyricsBlurMode()
        if (mode == AppleLyricsBlurPolicy.OFF) {
            // The clock/follow observer is not a blur effect. Keep it alive
            // when the user disables all visual blur, including the first
            // frame before any active-lines callback has registered a row.
            val adapter = appleRecyclerAdapter(recyclerView)
            if (adapter != null) {
                blurState.rememberRecycler(adapter, recyclerView)
                val songId = currentSongId()
                val revision = currentRevision()
                val now = SystemClock.elapsedRealtime()
                val active = appleLyricsNativeActiveAdapterPositionsOrNull(adapter)
                val activeChanged = AppleLyricsScrollIntentPolicy.observeNativeActive(
                    state, adapter, songId, revision, active, now,
                )
                val verifiedFocus = if (active != null && active.size > 1)
                    currentNativeFocusedAdapterPosition(adapter, active)
                else null
                val focusChanged = active != null &&
                    AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
                        state, adapter, songId, revision, active, verifiedFocus, now,
                    )
                if ((activeChanged || focusChanged) && !active.isNullOrEmpty()) {
                    scheduleAppleLyricsNativeFollowAfterSettle(recyclerView)
                }
                updateAppleLyricsOverlapFocusPoll(recyclerView, state, active)
            } else {
                updateAppleLyricsOverlapFocusPoll(recyclerView, state, emptySet())
            }
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "off",
                mode = mode,
            )
            return
        }
        val blurRadiusRange = appleLyricsBlurRadiusRange(mode)

        val adapter = appleRecyclerAdapter(recyclerView) ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "adapter_missing",
                mode = mode,
            )
            return
        }
        blurState.rememberRecycler(adapter, recyclerView)
        val observedSongId = currentSongId()
        val observedRevision = currentRevision()
        blurState.withRuntimeState(recyclerView) { currentState ->
            AppleLyricsScrollIntentPolicy.adoptAdapter(
                currentState, adapter, observedSongId, observedRevision,
            )
            AppleLyricsScrollIntentPolicy.onViewportHeightChanged(currentState, recyclerView.height)
        }
        val scrollState = appleLyricsRecyclerScrollState(recyclerView)
        if (scrollState != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            recordAppleLyricsScrollMotion(recyclerView, scrollState)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "scrolling",
                mode = mode,
                adapter = adapter,
            )
            return
        }
        AppleLyricsScrollIntentPolicy.endMotion(state)
        // Native reflection can be expensive; wait until the blur list is idle,
        // as before. Unlike the old blur-only updater, the first observer now
        // also establishes native follow even if Apple's callback runs later.
        val observedActive = appleLyricsNativeActiveAdapterPositionsOrNull(adapter)
        val activeChanged = AppleLyricsScrollIntentPolicy.observeNativeActive(
            state, adapter, observedSongId, observedRevision,
            observedActive, SystemClock.elapsedRealtime(),
        )
        updateAppleLyricsOverlapFocusPoll(recyclerView, state, observedActive)
        if (activeChanged && !observedActive.isNullOrEmpty()) {
            scheduleAppleLyricsNativeFollowAfterSettle(recyclerView)
        }

        val children = buildList {
            repeat(container.childCount) { index ->
                container.getChildAt(index)
                    ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                    ?.let(::add)
            }
        }
        if (children.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "children_empty",
                mode = mode,
                adapter = adapter,
            )
            return
        }

        val positionedChildren = children.mapNotNull { child ->
            appleLyricsChildAdapterPosition(recyclerView, child)
                .takeIf { it >= 0 }
                ?.let { position -> position to child }
        }
        val activePositions = observedActive.orEmpty()
        val currentPositionMs = appleLyricsCurrentPlaybackPositionMs()
        val playbackActivePosition = if (activePositions.size > 1)
            verifiedNativeWindows.focus(
                observedSongId, observedRevision, adapter, activePositions, currentPositionMs,
            )
        else null
        val singleActiveFocus = AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = activePositions,
            playbackAdapterPosition = playbackActivePosition,
            previousFocus = state.lastFocusedActivePosition,
        )
        if (singleActiveFocus != state.lastFocusedActivePosition && activePositions.size > 1) {
            AppleLyricUiRuntimeProbe.record(
                "native_focus_selected",
                "active=${activePositions.sorted()} selected=${singleActiveFocus ?: -1} " +
                    "clockAdapterPos=${playbackActivePosition ?: -1} " +
                    "playbackMs=${currentPositionMs ?: -1}",
            )
        }
        if (AppleLyricsScrollIntentPolicy.observeVerifiedNativeFocus(
                state, adapter, observedSongId, observedRevision,
                activePositions, playbackActivePosition,
                SystemClock.elapsedRealtime(),
            )
        ) {
            // No active-set callback is guaranteed at a native overlap clock
            // boundary. This blur frame may be the sole focus-change witness.
            scheduleAppleLyricsNativeFollowAfterSettle(recyclerView)
        }
        state.lastFocusedActivePosition = AppleLyricsBlurTransitionPolicy.nextFocusedPosition(
            observedNativeActive = observedActive,
            resolvedFocus = singleActiveFocus,
            previousFocus = state.lastFocusedActivePosition,
        )
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = singleActiveFocus?.let(::setOf).orEmpty(),
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val firstLineBeginMs = appleLyricsFirstLineBeginMs(adapter)
        val shouldBlurBeforeFirstLine = focusPositions.isEmpty() &&
            AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
        if (focusPositions.isEmpty()) {
            if (shouldBlurBeforeFirstLine) {
                state.settledAnchorTopY = null
                state.pendingOutgoingPositions = emptySet()
                state.outgoingZoneTopByPosition = emptyMap()
                val radiusByPosition = linkedMapOf<Int, Int>()
                positionedChildren
                    .sortedBy(Pair<Int, View>::first)
                    .forEachIndexed { visibleRowIndex, (position, child) ->
                        val radiusPx = AppleLyricsBlurPolicy.beforeFirstLineBlurRadiusPx(
                            mode = mode,
                            visibleRowIndex = visibleRowIndex,
                            minRadius = blurRadiusRange.start,
                            maxRadius = blurRadiusRange.endInclusive,
                            density = recyclerView.resources.displayMetrics.density,
                        )
                        radiusByPosition[position] = radiusPx
                        applyAppleLyricsBlur(
                            view = child,
                            mode = mode,
                            radiusPx = radiusPx,
                        )
                    }
                logAppleLyricsBlurDiagnostic(
                    recyclerView = recyclerView,
                    state = state,
                    stage = "before_first_line",
                    mode = mode,
                    adapter = adapter,
                    activePositions = activePositions,
                    instrumentalPositions = instrumentalPositions,
                    writersCreditsPositions = writersCreditsPositions,
                    focusPositions = focusPositions,
                    positionedChildren = positionedChildren,
                    radiusByPosition = radiusByPosition,
                    currentPositionMs = currentPositionMs,
                    firstLineBeginMs = firstLineBeginMs,
                )
                if (playbackHooks().isPlaying() && currentPositionMs != null && firstLineBeginMs != null) {
                    scheduleAppleLyricsBlur(
                        recyclerView = recyclerView,
                        delayMs = (firstLineBeginMs - currentPositionMs)
                            .coerceIn(APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS, APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS),
                    )
                }
                return
            }
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_empty",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
            return
        }
        val focusChildren = positionedChildren.filter { (position, _) ->
            position in focusPositions
        }
        if (focusChildren.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_not_visible",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }

        val initialAnchorY = recyclerView.height * APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION
        val anchorChild = focusChildren.minByOrNull { (_, child) ->
            abs(appleLyricsChildTopY(child) - (state.settledAnchorTopY ?: initialAnchorY))
        }?.second ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "anchor_child_missing",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        val anchorTopY = appleLyricsChildTopY(anchorChild)
        val settledAnchorTopY = state.settledAnchorTopY
        if (state.suspendedForScroll) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = if (state.pendingProgrammaticRecenterPosition == null) {
                    "awaiting_recenter_request"
                } else {
                    "awaiting_recenter_layout"
                },
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        if (settledAnchorTopY == null) {
            state.settledAnchorTopY = anchorTopY
        }

        val radiusByPosition = linkedMapOf<Int, Int>()
        val pendingOutgoingPositions = state.pendingOutgoingPositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition.toMutableMap()
        val deferredBlurPositions = linkedSetOf<Int>()
        val outgoingBottomByPosition = linkedMapOf<Int, Int>()
        val currentZoneTopByPosition = linkedMapOf<Int, Int>()
        positionedChildren.forEach { (childPosition, child) ->
            val rowDistance = focusPositions.minOf { focusPosition ->
                abs(childPosition - focusPosition)
            }
            val isPendingOutgoing = childPosition in pendingOutgoingPositions
            val currentZoneTopY = if (isPendingOutgoing) {
                outgoingZoneTopByPosition.getOrPut(childPosition) {
                    state.settledAnchorTopY ?: appleLyricsChildTopY(child)
                }
            } else {
                null
            }
            val rowBottomY = currentZoneTopY?.let { appleLyricsChildBottomY(child) }
            if (rowBottomY != null) {
                outgoingBottomByPosition[childPosition] = rowBottomY.roundToInt()
                currentZoneTopByPosition[childPosition] = currentZoneTopY.roundToInt()
            }
            val deferBlur = shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = isPendingOutgoing,
                rowBottomY = rowBottomY,
                currentZoneTopY = currentZoneTopY,
                // The old spatial grace kept non-playing lyrics fully sharp
                // after the next row started. Honor the verified current row.
                currentFocusResolved = singleActiveFocus != null,
            )
            if (deferBlur) deferredBlurPositions += childPosition
            val radiusPx = if (deferBlur) {
                0
            } else {
                AppleLyricsBlurPolicy.blurRadiusPx(
                    mode = mode,
                    rowDistance = rowDistance,
                    minRadius = blurRadiusRange.start,
                    maxRadius = blurRadiusRange.endInclusive,
                    density = recyclerView.resources.displayMetrics.density,
                )
            }
            radiusByPosition[childPosition] = radiusPx
            applyAppleLyricsBlur(
                view = child,
                mode = mode,
                radiusPx = radiusPx,
            )
        }
        state.pendingOutgoingPositions = deferredBlurPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
            .filterKeys { it in deferredBlurPositions }
        logAppleLyricsBlurDiagnostic(
            recyclerView = recyclerView,
            state = state,
            stage = "applied",
            mode = mode,
            adapter = adapter,
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
            focusPositions = focusPositions,
            positionedChildren = positionedChildren,
            radiusByPosition = radiusByPosition,
            deferredBlurPositions = deferredBlurPositions,
            outgoingBottomByPosition = outgoingBottomByPosition,
            currentZoneTopByPosition = currentZoneTopByPosition,
        )
        if (deferredBlurPositions.isNotEmpty()) {
            scheduleAppleLyricsBlur(
                recyclerView = recyclerView,
                delayMs = APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS,
            )
        }
    }

    fun hookAppleLyricsBlurEffect() {
        runCatching {
            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val passiveLifecycleMethods = listOf(
                "setAdapter" to 1,
                "onAttachedToWindow" to 0,
                "onLayout" to 5,
                "onChildAttachedToWindow" to 1,
            )
            val installedHooks = mutableListOf<String>()
            val failedHooks = mutableListOf<String>()
            passiveLifecycleMethods.forEach { (name, parameterCount) ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = parameterCount,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        if (name == "setAdapter") {
                            (chain.thisObject as? View)?.let(blurState::forgetClassification)
                        }
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                if (name == "setAdapter") {
                                    resetAppleLyricsBlurRuntimeState(recyclerView)
                                    rememberAppleLyricsRecyclerAdapter(recyclerView)
                                } else {
                                    if (name == "onChildAttachedToWindow") {
                                        // 只有复用且带过模糊状态的视图才可能有残留效果或未结束的动画；
                                        // 无状态视图直接跳过，避免每次挂载都执行效果清除
                                        (chain.args.firstOrNull() as? View)?.let { child ->
                                            val hasBlurState =
                                                blurState.containsRuntimeState(child)
                                            if (hasBlurState) {
                                                clearAppleLyricsBlur(child)
                                            }
                                        }
                                    }
                                    if (name == "onLayout") {
                                        rememberAppleLyricsRecyclerAdapter(recyclerView)
                                        completeAppleLyricsProgrammaticRecenter(recyclerView)
                                    }
                                    if (name == "onLayout" || name == "onAttachedToWindow") {
                                        (recyclerView as? View)?.let(::maybeRecoverAppleLyricsNativeFollow)
                                    }
                                    scheduleAppleLyricsBlur(recyclerView)
                                }
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            runCatching {
                val method = AppleReflection.findMethod(
                    recyclerClass,
                    "onScrolled",
                    parameterCount = 2,
                )
                hookRegistrar.installHook(method, after = { chain, _ ->
                    chain.thisObject
                        ?.takeIf(::isAppleLyricsRecyclerView)
                        ?.let { recyclerView ->
                            recordAppleLyricsScrollMotion(
                                recyclerView, appleLyricsRecyclerScrollState(recyclerView),
                            )
                            (recyclerView as? View)?.let(::maybeRecoverAppleLyricsNativeFollow)
                            scheduleAppleLyricsBlur(
                                recyclerView = recyclerView,
                                delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
                            )
                        }
                })
                installedHooks += "RecyclerView.onScrolled"
            }.onFailure { throwable ->
                failedHooks += "RecyclerView.onScrolled:${throwable.javaClass.simpleName}"
            }

            runCatching {
                val linearLayoutManagerClass = classLoader.loadClass(
                    "androidx.recyclerview.widget.LinearLayoutManager"
                )
                val method = findAppleLyricsScrollToPositionWithOffsetMethod(
                    linearLayoutManagerClass
                ) ?: error("scrollToPositionWithOffset method not found")
                hookRegistrar.installHook(method, after = { chain, _ ->
                    val targetPosition = (chain.args.firstOrNull() as? Number)?.toInt()
                        ?: return@installHook
                    onAppleLyricsProgrammaticRecenterRequested(
                        layoutManager = chain.thisObject,
                        targetPosition = targetPosition,
                    )
                })
                installedHooks += "LinearLayoutManager.${method.name}"
            }.onFailure { throwable ->
                failedHooks +=
                    "LinearLayoutManager.scrollToPositionWithOffset:" +
                        throwable.javaClass.simpleName
            }

            listOf("onScrollStateChanged", "setScrollState").forEach { name ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = 1,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        val scrollState = (chain.args.firstOrNull() as? Number)?.toInt()
                            ?: return@installHook
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                onAppleLyricsScrollStateChanged(recyclerView, scrollState)
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
                .forEach { resolvedClass ->
                val adapterClassName = resolvedClass.target.className
                runCatching {
                    val adapterClass = resolvedClass.clazz
                    val activeLinesMethodName = resolvedClass.target.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
                    )
                    val method = AppleReflection.findMethod(
                        adapterClass,
                        activeLinesMethodName,
                        parameterCount = 3,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        onAppleLyricsActiveLinesUpdated(chain.thisObject)
                    })
                    installedHooks += "$adapterClassName.$activeLinesMethodName"
                }.onFailure { throwable ->
                    failedHooks += "$adapterClassName.activeLines:${throwable.javaClass.simpleName}"
                }
            }
            check(installedHooks.isNotEmpty()) { "No RecyclerView lifecycle method was hookable" }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词模糊 Hook 明细: " +
                        "installed=$installedHooks, failed=$failedHooks"
                )
            }
            ProviderLogger.debug("Apple Music 歌词模糊 Hook 已安装: methods=${installedHooks.size}")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词模糊 Hook 安装失败", it)
        }
    }


}
