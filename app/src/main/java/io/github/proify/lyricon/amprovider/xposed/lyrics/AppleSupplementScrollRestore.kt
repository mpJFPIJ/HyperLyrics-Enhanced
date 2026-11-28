/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
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
import android.view.ViewTreeObserver
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
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
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
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
import kotlinx.serialization.encodeToString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.SharedPreferences

internal fun AppleLyricsSupplementHooks.ensureAppleLyricsScrollTracking(
    fragment: Any,
    songId: String,
    nativePresentationCompleted: Boolean = false,
) {
    // Apple Music 的 RecyclerView 由宿主 ClassLoader 加载，不能强转为模块侧
    // androidx.recyclerview.widget.RecyclerView。这里只依赖 framework View/ViewTreeObserver，
    // 位置读取和滚动调用统一交给已有的安全反射/ChildAdapterPosition 解析。
    val recycler = resolveAppleLyricsRecyclerView(fragment) as? ViewGroup ?: return
    if (scrollPresentationState.markTrackedIfNew(recycler)) {
        val recyclerRef = WeakReference(recycler)
        recycler.viewTreeObserver.addOnScrollChangedListener {
            if (scrollPresentationState.isPresentationInFlight() || isAppleLyricsScrollRestorePending()) return@addOnScrollChangedListener
            val currentRecycler = recyclerRef.get() ?: return@addOnScrollChangedListener
            currentAppleLyricsSongId?.let { currentSongId ->
                captureAppleLyricsScrollSnapshot(currentRecycler, currentSongId)
            }
        }
        recycler.addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            if (scrollPresentationState.isPresentationInFlight() || isAppleLyricsScrollRestorePending()) return@addOnLayoutChangeListener
            val currentRecycler = changedView as? ViewGroup ?: return@addOnLayoutChangeListener
            currentAppleLyricsSongId?.let { currentSongId ->
                captureAppleLyricsScrollSnapshot(currentRecycler, currentSongId)
            }
        }
        ProviderLogger.debug(
            "Apple Music 歌词滚动位置监听已绑定: id=$songId, " +
                "recycler=${System.identityHashCode(recycler)}"
        )
    }
    if (nativePresentationCompleted) {
        val adapter = blurHooks.appleRecyclerAdapter(recycler) ?: return
        val presentedAdapter = runCatching {
            lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD)
        }.getOrNull()
        if (currentAppleLyricsSongId == songId && adapter === presentedAdapter &&
            blurHooks.isAppleLyricsRecyclerAdapter(adapter)
        ) {
            scrollPresentationState.bindPresentedViewport(
                recycler, adapter, songId, hasAttachedRows = recycler.childCount > 0,
            )
        }
        // The outgoing children can survive the adapter swap until the next layout.
        // The listeners will capture the newly presented viewport after that layout.
        return
    }
    captureAppleLyricsScrollSnapshot(recycler, songId)
}

internal fun AppleLyricsSupplementHooks.isAppleLyricsScrollRestorePending(): Boolean =
    scrollPresentationState.isRestorePending()

internal fun AppleLyricsSupplementHooks.clearPendingAppleLyricsScrollRestore() {
    scrollPresentationState.takePendingRestore()?.let(::detachPendingAppleLyricsScrollRestore)
}

internal fun AppleLyricsSupplementHooks.cancelAppleLyricsRestoreOnUserDrag(recycler: View) {
    scrollPresentationState.cancelRestoreForUserDrag(recycler)?.let(::detachPendingAppleLyricsScrollRestore)
}

/** Removes the queued PreDraw listener from its host, if the host is still alive. */
private fun AppleLyricsSupplementHooks.detachPendingAppleLyricsScrollRestore(
    pending: AppleLyricsScrollPresentationState.PendingRestore,
) {
    val recycler = pending.host.get() as? ViewGroup ?: return
    runCatching {
        recycler.viewTreeObserver.removeOnPreDrawListener(pending.listener)
    }
    pending.attachListener?.let(recycler::removeOnAttachStateChangeListener)
}

internal fun AppleLyricsSupplementHooks.captureAppleLyricsScrollSnapshot(recycler: ViewGroup, songId: String) {
    if (currentAppleLyricsSongId != songId || recycler.isLayoutRequested) return
    val adapter = blurHooks.appleRecyclerAdapter(recycler) ?: return
    if (!scrollPresentationState.ownsViewport(recycler, adapter, songId)) return
    val firstChild = recycler.getChildAt(0) ?: return
    val position = blurHooks.appleLyricsChildAdapterPosition(recycler, firstChild)
    if (position < 0) return
    val activePositions = blurHooks.appleLyricsActiveAdapterPositions(adapter)
    val playbackPositionMs = appleLyricsCurrentPlaybackPositionMs()
    val positionedChildren = buildMap<Int, View> {
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            val childPosition = blurHooks.appleLyricsChildAdapterPosition(recycler, child)
            if (childPosition >= 0) put(childPosition, child)
        }
    }
    // A simultaneous outgoing/incoming native active set has no ordinal
    // meaning. Save the host's verified clock focus, or fall back to the
    // actual first viewport child without manufacturing an active anchor.
    val activeAdapterPosition = blurHooks.currentNativeFocusedAdapterPosition(
        adapter, activePositions, playbackPositionMs,
    )
    val detailedDiagnostics = BuildConfig.DEBUG &&
        (scrollPresentationState.isPresentationInFlight() || isAppleLyricsScrollRestorePending())
    val sourceTimingDebug = detailedDiagnostics.takeIf { it }
        ?.let { missingLyricsSupplement().timingDebugSnapshot(songId) }
    val adapterTimingDebug = detailedDiagnostics.takeIf { it }
        ?.let {
            blurHooks.appleLyricsAdapterDebugSnapshot(
                adapter = adapter,
                relevantPositions = positionedChildren.keys,
                playbackPositionMs = playbackPositionMs,
            )
        }
    val snapshot = AppleLyricsScrollPresentationState.ScrollSnapshot(
        firstPosition = position,
        firstOffset = firstChild.top,
        activeAdapterPosition = activeAdapterPosition,
        activeAdapterOffset = activeAdapterPosition
            ?.let(positionedChildren::get)
            ?.top,
        playbackPositionMs = playbackPositionMs,
        sourceTimingDebug = sourceTimingDebug,
        adapterTimingDebug = adapterTimingDebug,
    )
    val existing = scrollPresentationState.snapshotFor(songId)
    if (scrollPresentationState.shouldPreserveTopSnapshot(songId, position)) return
    scrollPresentationState.clearPreservedTopSnapshotIfScrolled(songId, position)
    if (shouldKeepAppleLyricsScrollSnapshot(
            existingPosition = existing?.firstPosition,
            capturedPosition = snapshot.firstPosition,
            presentationInFlight =
                scrollPresentationState.isPresentationInFlight() || isAppleLyricsScrollRestorePending(),
        )
    ) {
        return
    }
    if (!scrollPresentationState.acceptSnapshot(recycler, adapter, songId, snapshot)) return
    if (BuildConfig.DEBUG) {
        ProviderLogger.diagnostic(
            "Apple Music 歌词滚动快照已更新: id=$songId, " +
                "position=${snapshot.firstPosition}, offset=${snapshot.firstOffset}, " +
                "activePositions=${activePositions.sorted()}, " +
                "activeAnchor=${snapshot.activeAdapterPosition ?: "none"}, " +
                "activeAnchorOffset=${snapshot.activeAdapterOffset ?: "none"}, " +
                "playback=${snapshot.playbackPositionMs ?: "none"}, " +
                "presentationInFlight=${scrollPresentationState.isPresentationInFlight()}"
        )
        if (sourceTimingDebug != null || adapterTimingDebug != null) {
            ProviderLogger.diagnostic(
                "Apple Music 歌词映射诊断: stage=snapshot, id=$songId, " +
                    "source=[$sourceTimingDebug], adapter=[$adapterTimingDebug]"
            )
        }
    }
}

internal fun AppleLyricsSupplementHooks.restoreAppleLyricsScrollSnapshot(fragment: Any, songId: String) {
    val snapshot = scrollPresentationState.snapshotFor(songId)
        ?: run {
            scrollPresentationState.finishPresentation()
            return
        }
    val recycler = resolveAppleLyricsRecyclerView(fragment) as? ViewGroup
        ?: run {
            scrollPresentationState.finishPresentation()
            return
        }

    val currentScrollState = blurHooks.appleLyricsRecyclerScrollState(recycler)
    if (currentScrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
        cancelAppleLyricsRestoreOnUserDrag(recycler)
        scrollPresentationState.finishPresentation()
        return
    }
    if (!AppleLyricsScrollStatePolicy.mayRestore(currentScrollState)) {
        // The reflected state may fail transiently. Do not pre-position a
        // potentially user-dragged list based on an invented IDLE value.
        clearPendingAppleLyricsScrollRestore()
        scrollPresentationState.finishPresentation()
        return
    }
    clearPendingAppleLyricsScrollRestore()
    var attempts = 0
    var completed = false
    val attemptGate = AppleLyricsRestoreAttemptGate()
    lateinit var listener: ViewTreeObserver.OnPreDrawListener
    lateinit var attachListener: View.OnAttachStateChangeListener

    fun resolveRestoreTarget(): AppleLyricsScrollPresentationState.ResolvedRestoreTarget? {
        val layoutManager = runCatching {
            AppleReflection.call(recycler, "getLayoutManager")
        }.getOrNull() ?: return null
        val adapter = blurHooks.appleRecyclerAdapter(recycler) ?: return null
        if (!scrollPresentationState.ownsViewport(recycler, adapter, songId)) return null
        val itemCount = blurHooks.appleRecyclerAdapterItemCount(adapter)
        if (itemCount <= 0) return null
        val activePositions = blurHooks.appleLyricsActiveAdapterPositions(adapter)
        val playbackPositionMs = appleLyricsCurrentPlaybackPositionMs()
            ?: snapshot.playbackPositionMs
        val playbackMappedPosition = AppleLyricsPlaybackMappingPolicy.resolve(
            playbackPositionMs,
        ) { currentPlayback ->
            blurHooks.appleLyricsAdapterPositionForPlayback(
                adapter = adapter,
                playbackPositionMs = currentPlayback,
            )
        }
        val anchor = selectAppleLyricsRestoreAnchor(
            savedPosition = snapshot.firstPosition,
            savedOffset = snapshot.firstOffset,
            savedActivePosition = snapshot.activeAdapterPosition,
            savedActiveOffset = snapshot.activeAdapterOffset,
            currentActivePositions = activePositions,
            itemCount = itemCount,
            playbackMappedPosition = playbackMappedPosition,
        ) ?: return null
        val relevantPositions = buildSet {
            add(snapshot.firstPosition)
            snapshot.activeAdapterPosition?.let(::add)
            activePositions.forEach(::add)
            playbackMappedPosition?.let(::add)
        }
        return AppleLyricsScrollPresentationState.ResolvedRestoreTarget(
            layoutManager = layoutManager,
            itemCount = itemCount,
            anchor = anchor,
            activePositions = activePositions,
            playbackMappedPosition = playbackMappedPosition,
            sourceTimingDebug = BuildConfig.DEBUG
                .let { if (it) missingLyricsSupplement().timingDebugSnapshot(songId) else null },
            adapterTimingDebug = BuildConfig.DEBUG
                .let {
                    if (!it) {
                        null
                    } else {
                        blurHooks.appleLyricsAdapterDebugSnapshot(
                            adapter = adapter,
                            relevantPositions = relevantPositions,
                            playbackPositionMs = playbackPositionMs,
                        )
                    }
                },
        )
    }

    fun finishRestore(success: Boolean = false) {
        if (completed) return
        completed = true
        runCatching {
            recycler.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        recycler.removeOnAttachStateChangeListener(attachListener)
        // A newer restore may already own this state. Never let the detached
        // older listener clear its presentation gate or preserved anchor.
        if (scrollPresentationState.clearPendingRestoreIf(listener)) {
            if (!success) scrollPresentationState.markRestoreFailed(songId)
            scrollPresentationState.finishPresentation()
        }
    }

    // R2/I2 clears all RecyclerView children before the first layout of the new source.
    // Seed the host LayoutManager's pending target immediately so that first layout is
    // built at the preserved lyric position instead of drawing a transient top frame.
    resolveRestoreTarget()?.let { target ->
        val observed = blurHooks.appleLyricsRestorePositionedObservation(
            recycler, target.anchor.position,
        )
        val shouldDispatch = attemptGate.shouldDispatch(
                target.anchor.position,
                target.anchor.offset,
                observed.position,
                observed.offset,
            )
        val resolvedMethod = if (shouldDispatch) blurHooks.appleLyricsScrollToPositionWithOffset(
            layoutManager = target.layoutManager,
            position = target.anchor.position,
            offset = target.anchor.offset,
        ) else null
        if (shouldDispatch) attemptGate.noteDispatchResult(
            target.anchor.position, target.anchor.offset,
            observed.position, observed.offset, dispatched = resolvedMethod != null,
        )
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 歌词滚动预定位: id=$songId, " +
                    "target=${target.anchor.position}, offset=${target.anchor.offset}, " +
                    "activePositions=${target.activePositions.sorted()}, " +
                    "activeTarget=${target.anchor.activePosition ?: "none"}, " +
                    "playbackTarget=${target.playbackMappedPosition ?: "none"}, " +
                    "itemCount=${target.itemCount}, " +
                    "method=${resolvedMethod ?: "unresolved"}, " +
                    "savedSource=[${snapshot.sourceTimingDebug ?: "none"}], " +
                    "currentSource=[${target.sourceTimingDebug ?: "none"}], " +
                    "savedAdapter=[${snapshot.adapterTimingDebug ?: "none"}], " +
                    "currentAdapter=[${target.adapterTimingDebug ?: "none"}]"
            )
        }
    }

    listener = ViewTreeObserver.OnPreDrawListener {
        if (!scrollPresentationState.ownsPendingRestore(listener)) return@OnPreDrawListener true
        val sampledScrollState = blurHooks.appleLyricsRecyclerScrollState(recycler)
        if (sampledScrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
            cancelAppleLyricsRestoreOnUserDrag(recycler)
            return@OnPreDrawListener true
        }
        if (!AppleLyricsScrollStatePolicy.mayRestore(sampledScrollState)) {
            // No scrolling, no success claim. The next pre-draw may have a
            // trustworthy native state; the existing timeout bounds retries.
            return@OnPreDrawListener true
        }
        attempts += 1
        if (currentAppleLyricsSongId != songId) {
            finishRestore()
            return@OnPreDrawListener true
        }
        val restoreTarget = resolveRestoreTarget()
        if (restoreTarget == null) {
            if (attempts >= 8) finishRestore()
            return@OnPreDrawListener true
        }
        val layoutManager = restoreTarget.layoutManager
        val itemCount = restoreTarget.itemCount
        val targetPosition = restoreTarget.anchor.position
        val targetOffset = restoreTarget.anchor.offset
        val observed = blurHooks.appleLyricsRestorePositionedObservation(
            recycler, targetPosition,
        )
        val currentPosition = observed.position
        val currentOffset = observed.offset
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 歌词滚动恢复尝试: id=$songId, attempt=$attempts, " +
                    "currentPosition=${currentPosition ?: "none"}, " +
                    "currentOffset=${currentOffset ?: "none"}, target=$targetPosition, " +
                    "targetOffset=$targetOffset, " +
                    "activePositions=${restoreTarget.activePositions.sorted()}, " +
                    "activeTarget=${restoreTarget.anchor.activePosition ?: "none"}, " +
                    "playbackTarget=${restoreTarget.playbackMappedPosition ?: "none"}, " +
                    "saved=${snapshot.firstPosition}, itemCount=$itemCount, " +
                    "adapter=${blurHooks.appleRecyclerAdapter(recycler)?.javaClass?.name ?: "none"}, " +
                    "adapterIdentity=${blurHooks.appleRecyclerAdapter(recycler)
                        ?.let(System::identityHashCode) ?: 0}, " +
                    "savedSource=[${snapshot.sourceTimingDebug ?: "none"}], " +
                    "currentSource=[${restoreTarget.sourceTimingDebug ?: "none"}], " +
                    "savedAdapter=[${snapshot.adapterTimingDebug ?: "none"}], " +
                    "currentAdapter=[${restoreTarget.adapterTimingDebug ?: "none"}], " +
                    "visible=${debugAppleLyricsVisibleChildren(recycler)}"
            )
        }
        if (
            currentPosition == targetPosition &&
            currentOffset != null &&
            abs(currentOffset - targetOffset) <= 2
        ) {
            finishRestore(success = true)
            return@OnPreDrawListener true
        }
        if (!attemptGate.shouldDispatch(
                targetPosition, targetOffset, currentPosition, currentOffset,
            )
        ) {
            if (attempts == 2) {
                AppleLyricUiRuntimeProbe.record(
                    "scroll_restore_duplicate_suppressed",
                    "attempt=$attempts target=$targetPosition " +
                        "observed=${currentPosition ?: -1} " +
                        "itemCount=$itemCount",
                )
            }
            if (attempts >= 8) finishRestore()
            return@OnPreDrawListener true
        }
        val resolvedMethod = blurHooks.appleLyricsScrollToPositionWithOffset(
            layoutManager = layoutManager,
            position = targetPosition,
            offset = targetOffset,
        )
        attemptGate.noteDispatchResult(
            targetPosition, targetOffset, currentPosition, currentOffset,
            dispatched = resolvedMethod != null,
        )
        if (resolvedMethod != null) {
            ProviderLogger.debug(
                "Apple Music 歌词滚动位置已恢复: id=$songId, " +
                    "position=$targetPosition, offset=$targetOffset, " +
                    "attempt=$attempts, method=$resolvedMethod"
            )
        } else {
            ProviderLogger.error(
                "Apple Music 歌词滚动位置恢复调用失败: id=$songId, " +
                    "position=$targetPosition, layout=${layoutManager.javaClass.name}",
            )
        }
        if (attempts >= 8) finishRestore()
        true
    }
    attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            // A detached RecyclerView need not receive another PreDraw. Release
            // its listener immediately rather than retaining the whole host tree.
            finishRestore()
        }
    }
    scrollPresentationState.setPendingRestore(recycler, listener, attachListener)
    recycler.addOnAttachStateChangeListener(attachListener)
    recycler.viewTreeObserver.addOnPreDrawListener(listener)
    recycler.postOnAnimation {
        if (!recycler.isAttachedToWindow && !completed) {
            finishRestore()
        }
    }
    // A RecyclerView registered before its first attach need not run either
    // PreDraw or a View-posted callback. The main Handler always runs; expire
    // only this exact restore, never a newer one for the same host.
    mainHandler.postDelayed({
        if (!completed && scrollPresentationState.ownsPendingRestore(listener)) {
            finishRestore()
        }
    }, 2_000L)
}

internal fun AppleLyricsSupplementHooks.scheduleSupplementActiveLineUpdate() {
    activeLineUpdateController.schedule()
}

/**
 * Apple 没有为补充歌词持续下发 active line（adapter.B() 始终为空），但点击歌词
 * seek 仍正常，说明 adapter 只缺 T 的激活集合。这里按补充 Store 自己的行时间
 * 计算当前句，并在 index 变化时补发与 Apple 跳转歌词相同的行级 T 调用。
 */
internal fun AppleLyricsSupplementHooks.updateSupplementActiveLine() {
    val fragment = presentationBinding.fragment() ?: run {
            stopSupplementActiveLineUpdate()
            return
        }
    val recyclerView = resolveAppleLyricsRecyclerView(fragment)
        ?: run {
            stopSupplementActiveLineUpdate()
            return
        }
    val adapter = appleRecyclerAdapter(recyclerView)
        ?: run {
            stopSupplementActiveLineUpdate()
            return
        }
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    val visibleSongId = currentAppleLyricsSongId?.takeIf(String::isNotBlank)
    val songId = queueSongId ?: visibleSongId ?: run {
        stopSupplementActiveLineUpdate()
        return
    }
    if (queueSongId != null && visibleSongId != null && queueSongId != visibleSongId) {
        stopSupplementActiveLineUpdate()
        return
    }
    if (
        !missingLyricsSupplement().hasSupplementContent(songId) ||
        missingLyricsSupplement().hasKnownNativeLyricsFor(songId)
    ) {
        stopSupplementActiveLineUpdate()
        return
    }
    val position = appleLyricsSupplementPlaybackPositionMs()
        ?: return scheduleSupplementActiveLineUpdate()
    val lines = missingLyricsSupplement().store.lines(songId)
    if (lines.isEmpty()) {
        stopSupplementActiveLineUpdate()
        return
    }
    val lineIndex = activeSupplementLineIndex(lines, position) ?: -1
    activeLineUpdateController.applyIfChanged(adapter, lineIndex) {
        val methodName = blurHooks.lyricsAdapterMember(
            adapter,
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
        )
        val emptyPairs = java.lang.reflect.Array.newInstance(
            classLoader.loadClass("android.util.Pair"),
            0,
        )
        AppleReflection.call(
            adapter,
            methodName,
            if (lineIndex >= 0) listOf(lineIndex) else emptyList<Int>(),
            -1,
            emptyPairs,
        )
    }?.onFailure {
        // Loading/empty adapters have no lyrics members. The next tick retries
        // when the host installs its lyric adapter (including supported subclasses).
        if (blurHooks.isAppleLyricsRecyclerAdapter(adapter)) {
            ProviderLogger.error("Apple Music 无歌词补充激活行补发失败", it)
        }
    }?.onSuccess {
        ProviderLogger.debug(
            "Apple Music 无歌词补充激活行补发: " +
                "id=$songId, index=$lineIndex, position=$position"
        )
    }
    scheduleSupplementActiveLineUpdate()
}

internal fun AppleLyricsSupplementHooks.stopSupplementActiveLineUpdate() {
    activeLineUpdateController.stop()
}
