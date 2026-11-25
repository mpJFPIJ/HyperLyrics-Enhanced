/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.view.ViewTreeObserver
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsScrollPresentationStateTest {
    @Test fun `a temporarily unavailable native adapter mapping is retried on the next layout`() {
        var calls = 0
        assertNull(AppleLyricsPlaybackMappingPolicy.resolve(1_000L) {
            calls++
            null // Adapter has not populated its native lyric rows yet.
        })
        assertEquals(7, AppleLyricsPlaybackMappingPolicy.resolve(1_000L) {
            calls++
            7
        })
        assertEquals(9, AppleLyricsPlaybackMappingPolicy.resolve(1_000L) {
            calls++
            9 // Verified native windows may change without changing the clock.
        })
        assertEquals(3, calls)
    }

    @Test fun `seek and adapter changes always query the current native mapping`() {
        assertEquals(2, AppleLyricsPlaybackMappingPolicy.resolve(1_000L) { 2 })
        assertEquals(15, AppleLyricsPlaybackMappingPolicy.resolve(27_000L) { 15 })
        assertEquals(3, AppleLyricsPlaybackMappingPolicy.resolve(27_000L) { 3 })
        assertNull(AppleLyricsPlaybackMappingPolicy.resolve(null) { 99 })
        assertEquals(4, AppleLyricsPlaybackMappingPolicy.resolve(28_000L) { 4 })
    }

    @Test fun `same paused clock cannot preserve a position after active native rows change`() {
        var activeRow = 1
        assertEquals(1, AppleLyricsPlaybackMappingPolicy.resolve(5_000L) { activeRow })
        activeRow = 4
        assertEquals(4, AppleLyricsPlaybackMappingPolicy.resolve(5_000L) { activeRow })
        assertNull(AppleLyricsPlaybackMappingPolicy.resolve(5_000L) { -1 })
    }

    @Test
    fun `restore observes positioned target instead of first disappearing recycler child`() {
        val observed = AppleLyricsRestoreObservationPolicy.select(
            targetPosition = 0,
            children = listOf(
                AppleLyricsRestoreObservedChild(null, 1483),
                AppleLyricsRestoreObservedChild(0, 54),
                AppleLyricsRestoreObservedChild(1, 115),
            ),
        )
        assertEquals(0, observed.position)
        assertEquals(54, observed.offset)
        assertTrue(AppleLyricsVisibleViewportPolicy.intersects(
            childLeft = 0, childTop = 54, childRight = 300, childBottom = 115,
            viewportWidth = 300, viewportHeight = 600,
        ))
        assertFalse(AppleLyricsVisibleViewportPolicy.intersects(
            childLeft = 0, childTop = 1483, childRight = 300, childBottom = 1560,
            viewportWidth = 300, viewportHeight = 600,
        ))
    }

    @Test
    fun `restore detects progress when target row is still outside positioned children`() {
        val observation = AppleLyricsRestoreObservationPolicy.select(
            targetPosition = 9,
            children = listOf(
                AppleLyricsRestoreObservedChild(null, -500),
                AppleLyricsRestoreObservedChild(6, -125),
                AppleLyricsRestoreObservedChild(7, 20),
            ),
        )
        assertEquals(6, observation.position)
        assertEquals(-125, observation.offset)
    }

    @Test
    fun `repeated identical restore requests do not fight eight consecutive native layouts`() {
        val gate = AppleLyricsRestoreAttemptGate()
        assertTrue(gate.shouldDispatch(0, -110, null, 1483))
        repeat(3) {
            assertFalse(gate.shouldDispatch(0, -110, null, 1483))
        }
        // One bounded retry remains when the first native call was ignored.
        assertTrue(gate.shouldDispatch(0, -110, null, 1483))
        repeat(4) {
            assertFalse(gate.shouldDispatch(0, -110, null, 1483))
        }
    }

    @Test
    fun `scroll restore permits changed target or observable native movement`() {
        val gate = AppleLyricsRestoreAttemptGate()
        assertTrue(gate.shouldDispatch(5, 120, 0, 300))
        assertFalse(gate.shouldDispatch(5, 120, 0, 300))
        assertTrue(gate.shouldDispatch(5, 120, 1, 180))
        assertFalse(gate.shouldDispatch(5, 120, 1, 180))
        assertTrue(gate.shouldDispatch(6, 120, 1, 180))
        assertTrue(gate.shouldDispatch(6, 90, 1, 180))
    }

    private class Listener : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean = true
    }

    private fun snapshot(firstPosition: Int, offset: Int = 0) =
        AppleLyricsScrollPresentationState.ScrollSnapshot(
            firstPosition = firstPosition,
            firstOffset = offset,
            activeAdapterPosition = null,
            activeAdapterOffset = null,
            playbackPositionMs = null,
        )

    @Test
    fun `previous adapter cannot supply a viewport after a track change`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val adapterA = Any()
        val adapterB = Any()
        state.bindPresentedViewport(host, adapterA, "A", hasAttachedRows = false)
        assertTrue(state.ownsViewport(host, adapterA, "A"))
        assertTrue(state.acceptSnapshot(host, adapterA, "A", snapshot(35)))

        state.clearSnapshot()
        assertNull(state.snapshotFor("B"))
        assertFalse(state.ownsViewport(host, adapterA, "B"))
        assertFalse(state.acceptSnapshot(host, adapterA, "B", snapshot(35)))
        // The native callback can return while the old adapter is still installed.
        state.bindPresentedViewport(host, adapterA, "B", hasAttachedRows = false)
        assertFalse(state.ownsViewport(host, adapterA, "B"))
        state.bindPresentedViewport(host, adapterB, "B", hasAttachedRows = true)
        assertFalse(state.ownsViewport(host, adapterB, "B"))
        state.bindPresentedViewport(host, adapterB, "B", hasAttachedRows = false)
        assertTrue(state.ownsViewport(host, adapterB, "B"))
        assertFalse(state.ownsViewport(Any(), adapterB, "B"))
        assertFalse(state.ownsViewport(host, adapterA, "B"))
        assertTrue(state.acceptSnapshot(host, adapterB, "B", snapshot(2)))
        state.bindPresentedViewport(host, Any(), "B", hasAttachedRows = false)
        assertEquals(2, state.snapshotFor("B")?.firstPosition)
        state.bindPresentedViewport(Any(), Any(), "B", hasAttachedRows = false)
        assertNull(state.snapshotFor("B"))

        state.clearSnapshot()
        state.bindPresentedViewport(host, adapterA, "A", hasAttachedRows = false)
        assertFalse(state.ownsViewport(host, adapterA, "A"))
    }

    @Test
    fun `drag cancels only the matching hosts restore and releases the preserved anchor`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val listener = Listener()
        val adapter = Any()
        state.bindPresentedViewport(host, adapter, "A", hasAttachedRows = false)
        assertTrue(state.acceptSnapshot(host, adapter, "A", snapshot(35)))
        state.markRestoreFailed("A")
        state.beginPresentation()
        state.setPendingRestore(host, listener)

        assertNull(state.cancelRestoreForUserDrag(Any()))
        assertTrue(state.ownsPendingRestore(listener))
        assertSame(listener, state.cancelRestoreForUserDrag(host)?.listener)
        assertFalse(state.ownsPendingRestore(listener))
        assertFalse(state.isPresentationInFlight())
        assertFalse(state.shouldPreserveTopSnapshot("A", 0))
        assertNull(state.snapshotFor("A"))
    }

    private class LyricsAdapter {
        val appliedRows = mutableListOf<Int>()
        fun updateRow(index: Int) {
            appliedRows.add(index)
        }
    }

    @Test
    fun `placeholder member failure leaves the row available for the replacement adapter`() {
        val controller = AppleLyricsActiveLineUpdateController({ _, _ -> }, 500L) {}
        fun tick(adapter: Any) = controller.applyIfChanged(adapter, 4) {
            adapter.javaClass.getDeclaredMethod("updateRow", Int::class.javaPrimitiveType)
                .invoke(adapter, 4)
        }

        assertTrue(tick(Any())?.exceptionOrNull() is NoSuchMethodException)
        val first = LyricsAdapter()
        assertTrue(tick(first)?.isSuccess == true)
        assertNull(tick(first))
        val replacement = LyricsAdapter()
        assertTrue(tick(replacement)?.isSuccess == true)
        assertEquals(listOf(4), first.appliedRows)
        assertEquals(listOf(4), replacement.appliedRows)
    }

    @Test
    fun `snapshot is addressed by song and cleared on track change`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val adapter = Any()
        state.bindPresentedViewport(host, adapter, "a", hasAttachedRows = false)
        assertTrue(state.acceptSnapshot(host, adapter, "a", snapshot(3)))
        assertEquals(3, state.snapshotFor("a")?.firstPosition)
        assertNull(state.snapshotFor("b"))
        state.clearSnapshot()
        assertNull(state.snapshotFor("a"))
    }

    @Test
    fun `failed restore preserves the last good anchor until real scrolling returns`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val adapter = Any()
        state.bindPresentedViewport(host, adapter, "a", hasAttachedRows = false)
        assertTrue(state.acceptSnapshot(host, adapter, "a", snapshot(7)))
        state.markRestoreFailed("a")

        // Apple's transient top layout must not overwrite the preserved anchor.
        assertTrue(state.shouldPreserveTopSnapshot("a", 0))
        assertFalse(state.shouldPreserveTopSnapshot("a", 4))
        // A different song is not protected by this song's failed restore.
        assertFalse(state.shouldPreserveTopSnapshot("b", 0))

        state.clearPreservedTopSnapshotIfScrolled("a", 4)
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
    }

    @Test
    fun `top preservation needs an existing positive anchor`() {
        val state = AppleLyricsScrollPresentationState()
        state.markRestoreFailed("a")
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
        val host = Any()
        val adapter = Any()
        state.bindPresentedViewport(host, adapter, "a", hasAttachedRows = false)
        assertTrue(state.acceptSnapshot(host, adapter, "a", snapshot(0)))
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
    }

    @Test
    fun `pending restore tracks liveness and clears by listener identity`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val first = Listener()
        val second = Listener()

        state.setPendingRestore(host, first)
        assertTrue(state.isRestorePending())
        assertTrue(state.ownsPendingRestore(first))
        assertFalse(state.ownsPendingRestore(second))
        assertSame(first, state.takePendingRestore()?.listener)
        assertFalse(state.isRestorePending())
        assertNull(state.takePendingRestore())

        state.setPendingRestore(host, first)
        // A newer restore must not be cancelled by an older listener finishing.
        assertFalse(state.clearPendingRestoreIf(second))
        assertTrue(state.isRestorePending())
        assertTrue(state.clearPendingRestoreIf(first))
        assertFalse(state.isRestorePending())
    }

    @Test
    fun `pending restore owns its detach cleanup listener for explicit cancellation`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val preDraw = Listener()
        val onDetach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        state.setPendingRestore(host, preDraw, onDetach)
        val pending = requireNotNull(state.takePendingRestore())
        assertSame(preDraw, pending.listener)
        assertSame(onDetach, pending.attachListener)
    }

    @Test
    fun `a newer restore replaces the previously pending one`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val first = Listener()
        val second = Listener()

        state.setPendingRestore(host, first)
        state.setPendingRestore(host, second)
        assertFalse(state.ownsPendingRestore(first))
        assertTrue(state.ownsPendingRestore(second))
        // The older listener no longer owns the pending entry.
        assertFalse(state.clearPendingRestoreIf(first))
        assertTrue(state.isRestorePending())
        assertSame(second, state.takePendingRestore()?.listener)
    }

    @Test
    fun `tracked hosts are registered once per instance`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        assertTrue(state.markTrackedIfNew(host))
        assertFalse(state.markTrackedIfNew(host))
        assertTrue(state.markTrackedIfNew(Any()))
    }

    @Test
    fun `presentation in flight survives until an explicit finish`() {
        val state = AppleLyricsScrollPresentationState()
        assertFalse(state.isPresentationInFlight())
        state.beginPresentation()
        state.beginPresentation()
        assertTrue(state.isPresentationInFlight())
        state.finishPresentation()
        assertFalse(state.isPresentationInFlight())
    }

    @Test
    fun `active line scheduling is de-duplicated and interval bounded`() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        var updates = 0
        val controller = AppleLyricsActiveLineUpdateController(
            post = { task, delayMs -> posted += task to delayMs },
            intervalMs = 500L,
            update = { updates++ },
        )

        controller.schedule()
        controller.schedule()
        assertEquals(1, posted.size)
        assertEquals(500L, posted.single().second)

        posted.single().first.run()
        assertEquals(1, updates)
        // A finished turn allows the next reschedule instead of wedging the loop.
        controller.schedule()
        assertEquals(2, posted.size)
    }

    @Test
    fun `stop invalidates applied row including a real no active lyrics gap`() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        var updates = 0
        val controller = AppleLyricsActiveLineUpdateController(
            post = { task, delayMs -> posted += task to delayMs },
            intervalMs = 500L,
            update = { updates++ },
        )
        controller.schedule()
        val adapter = Any()
        assertTrue(controller.applyIfChanged(adapter, 4) {}?.isSuccess == true)

        controller.stop()
        // -1 is now the legitimate no-active-line state; the reset sentinel
        // must be distinct so the next update will actually clear stale rows.
        assertTrue(controller.applyIfChanged(adapter, -1) {}?.isSuccess == true)
        assertNull(controller.applyIfChanged(adapter, -1) {})
        controller.stop()
        assertTrue(controller.applyIfChanged(adapter, -1) {}?.isSuccess == true)
        // The already queued callback is not cancelled by the original contract.
        posted.single().first.run()
        assertEquals(1, updates)
    }
}
