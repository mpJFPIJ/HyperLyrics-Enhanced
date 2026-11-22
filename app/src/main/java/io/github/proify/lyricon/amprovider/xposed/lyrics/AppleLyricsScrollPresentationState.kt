/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.view.ViewTreeObserver
import android.view.View
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the state of one Apple lyrics scroll/presentation transition.
 *
 * The owner deliberately stores only immutable snapshots, weak host references and listener
 * identity; reflection, adapter inspection and host callbacks stay in the extension functions.
 * Callers cannot replace a snapshot, a host token or a listener without a named transition.
 * Hosts are held as opaque weak tokens because the owner only needs liveness and identity.
 */
internal class AppleLyricsScrollPresentationState {
    internal data class ScrollSnapshot(
        val firstPosition: Int,
        val firstOffset: Int,
        val activeAdapterPosition: Int?,
        val activeAdapterOffset: Int?,
        val playbackPositionMs: Long?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )

    internal data class PendingRestore(
        val host: WeakReference<Any>,
        val listener: ViewTreeObserver.OnPreDrawListener,
        val attachListener: View.OnAttachStateChangeListener? = null,
    )

    internal data class ResolvedRestoreTarget(
        val layoutManager: Any,
        val itemCount: Int,
        val anchor: AppleLyricsRestoreAnchor,
        val activePositions: Set<Int>,
        val playbackMappedPosition: Int?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )

    // A completed native presentation can still expose the outgoing adapter.
    // Remember its original visit even after clearing the current viewport.
    private data class AdapterVisit(val songId: String, val visit: Long)
    private val adapterVisits = WeakIdentityMap<Any, AdapterVisit>()
    private var visit = 0L
    private var viewportHost = WeakReference<Any>(null)
    private var viewportAdapter = WeakReference<Any>(null)

    @Synchronized
    fun bindPresentedViewport(host: Any, adapter: Any, songId: String, hasAttachedRows: Boolean) {
        val current = AdapterVisit(songId, visit)
        val owner = adapterVisits[adapter]
        if (owner == null) {
            // Unknown, already populated adapters may still be showing the previous song.
            if (hasAttachedRows) return
            adapterVisits[adapter] = current
        } else if (owner != current) {
            return
        }
        if (viewportHost.get() !== host) {
            snapshot = null
            snapshotSongId = null
            preservedTopSnapshotSongId = null
        }
        viewportHost = WeakReference(host)
        viewportAdapter = WeakReference(adapter)
    }

    @Synchronized
    fun ownsViewport(host: Any, adapter: Any, songId: String): Boolean =
        viewportHost.get() === host && viewportAdapter.get() === adapter &&
            adapterVisits[adapter]?.songId == songId

    private var snapshot: ScrollSnapshot? = null
    private var snapshotSongId: String? = null
    private var preservedTopSnapshotSongId: String? = null
    private var pendingRestore: PendingRestore? = null
    private var presentationInFlight = false
    private val trackedHosts = Collections.newSetFromMap(
        WeakHashMap<Any, Boolean>()
    )

    @Synchronized
    fun markTrackedIfNew(host: Any): Boolean = trackedHosts.add(host)

    @Synchronized
    fun isPresentationInFlight(): Boolean = presentationInFlight

    @Synchronized
    fun beginPresentation() {
        presentationInFlight = true
    }

    @Synchronized
    fun finishPresentation() {
        presentationInFlight = false
    }

    @Synchronized
    fun isRestorePending(): Boolean = pendingRestore?.host?.get() != null

    @Synchronized
    fun ownsPendingRestore(listener: ViewTreeObserver.OnPreDrawListener): Boolean =
        pendingRestore?.listener === listener

    @Synchronized
    fun snapshotFor(songId: String): ScrollSnapshot? =
        snapshot?.takeIf { snapshotSongId == songId }

    @Synchronized
    fun shouldPreserveTopSnapshot(songId: String, capturedPosition: Int): Boolean =
        preservedTopSnapshotSongId == songId && capturedPosition == 0 &&
            snapshotFor(songId)?.firstPosition?.let { it > 0 } == true

    @Synchronized
    fun clearPreservedTopSnapshotIfScrolled(songId: String, capturedPosition: Int) {
        if (preservedTopSnapshotSongId == songId && capturedPosition > 0) {
            preservedTopSnapshotSongId = null
        }
    }

    @Synchronized
    fun acceptSnapshot(host: Any, adapter: Any, songId: String, value: ScrollSnapshot): Boolean {
        if (!ownsViewport(host, adapter, songId)) return false
        snapshot = value
        snapshotSongId = songId
        return true
    }

    @Synchronized
    fun clearSnapshot() {
        visit++
        viewportHost.clear()
        viewportAdapter.clear()
        preservedTopSnapshotSongId = null
        snapshot = null
        snapshotSongId = null
    }

    @Synchronized
    fun markRestoreFailed(songId: String) {
        preservedTopSnapshotSongId = songId
    }

    @Synchronized
    fun setPendingRestore(
        host: Any,
        listener: ViewTreeObserver.OnPreDrawListener,
        attachListener: View.OnAttachStateChangeListener? = null,
    ) {
        pendingRestore = PendingRestore(WeakReference(host), listener, attachListener)
    }

    @Synchronized
    fun takePendingRestore(): PendingRestore? = pendingRestore.also { pendingRestore = null }

    @Synchronized
    fun cancelRestoreForUserDrag(host: Any): PendingRestore? {
        val pending = pendingRestore ?: return null
        if (pending.host.get() !== host) return null
        pendingRestore = null
        presentationInFlight = false
        preservedTopSnapshotSongId = null
        snapshot = null
        snapshotSongId = null
        return pending
    }

    /** Clears the pending entry only when it belongs to [listener]; a newer restore is left intact. */
    @Synchronized
    fun clearPendingRestoreIf(listener: ViewTreeObserver.OnPreDrawListener): Boolean {
        if (pendingRestore?.listener !== listener) return false
        pendingRestore = null
        return true
    }
}

/**
 * Owns active-line rescheduling and the last applied row.
 *
 * [stop] deliberately only invalidates the last applied row: a callback already queued keeps
 * the update loop alive for one more turn, which is the behaviour this component was
 * extracted from. Posting is injected so the scheduling contract stays verifiable.
 */
internal class AppleLyricsActiveLineUpdateController(
    private val post: (Runnable, Long) -> Unit,
    private val intervalMs: Long,
    private val update: () -> Unit,
) {
    private var scheduled = false
    // -1 is a real, applied "no active lyrics" state; use a distinct initial
    // sentinel so a gap first observed after a song change clears stale state.
    private var lastAppliedIndex = Int.MIN_VALUE
    private var lastAppliedAdapter = WeakReference<Any>(null)
    private val runnable = Runnable {
        synchronized(this) {
            scheduled = false
        }
        update()
    }

    @Synchronized
    fun schedule() {
        if (scheduled) return
        scheduled = true
        post(runnable, intervalMs)
    }

    fun applyIfChanged(adapter: Any, index: Int, apply: () -> Unit): Result<Unit>? {
        synchronized(this) {
            if (lastAppliedAdapter.get() === adapter && lastAppliedIndex == index) return null
        }
        // Resolving members and constructing arguments can both fail before invocation.
        return runCatching(apply).onSuccess {
            synchronized(this) {
                lastAppliedAdapter = WeakReference(adapter)
                lastAppliedIndex = index
            }
        }
    }

    @Synchronized
    fun stop() {
        lastAppliedAdapter.clear()
        lastAppliedIndex = Int.MIN_VALUE
    }
}

/** Apple's verified mapping also depends on its changing active-row set and
 * holder-bound timing windows, not merely on adapter identity, row count or
 * playback clock. Query that inexpensive live map for *every* restore attempt;
 * caching even a successful lookup can preserve a stale row after source swap. */
internal object AppleLyricsPlaybackMappingPolicy {
    fun resolve(
        positionMs: Long?,
        findPosition: (Long) -> Int?,
    ): Int? = positionMs?.let(findPosition)?.takeIf { it >= 0 }
}

/**
 * Native RecyclerView may report the same unfinished layout for eight
 * consecutive PreDraw callbacks. Do not issue eight identical scroll commands:
 * allow the first, one delayed retry, and any command whose target or observed
 * native layout genuinely changed. Each restore owns a fresh instance.
 */
internal class AppleLyricsRestoreAttemptGate {
    private data class Observation(
        val targetPosition: Int,
        val targetOffset: Int,
        val observedPosition: Int?,
        val observedOffset: Int?,
    )

    private var last: Observation? = null
    private var unchangedSamples = 0
    private var identicalDispatches = 0

    fun shouldDispatch(
        targetPosition: Int,
        targetOffset: Int,
        observedPosition: Int?,
        observedOffset: Int?,
    ): Boolean {
        val current = Observation(
            targetPosition, targetOffset, observedPosition, observedOffset,
        )
        if (last != current) {
            last = current
            unchangedSamples = 0
            identicalDispatches = 1
            return true
        }
        unchangedSamples++
        if (identicalDispatches >= 2 || unchangedSamples < 4) return false
        identicalDispatches++
        return true
    }
}

internal data class AppleLyricsRestoreObservedChild(val position: Int?, val offset: Int?)

/** Ignore disappearing RecyclerView children with NO valid adapter position. */
internal object AppleLyricsRestoreObservationPolicy {
    fun select(
        targetPosition: Int,
        children: List<AppleLyricsRestoreObservedChild>,
    ): AppleLyricsRestoreObservedChild =
        children.firstOrNull { it.position == targetPosition } ?:
            children.firstOrNull { it.position != null && it.position >= 0 } ?:
            AppleLyricsRestoreObservedChild(null, null)
}

/** Only physically intersecting lyrics count as on-screen for native follow diagnostics. */
internal object AppleLyricsVisibleViewportPolicy {
    fun intersects(
        childLeft: Int,
        childTop: Int,
        childRight: Int,
        childBottom: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean = viewportWidth > 0 && viewportHeight > 0 &&
        childRight > 0 && childLeft < viewportWidth &&
        childBottom > 0 && childTop < viewportHeight
}
