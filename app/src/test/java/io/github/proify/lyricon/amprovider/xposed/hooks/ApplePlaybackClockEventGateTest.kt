package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ApplePlaybackClockEventGateTest {
    @Test fun lateReadingStartedBeforeAPausedSeekNeverPublishesOldLyrics() {
        val gate = ApplePlaybackClockEventGate()
        val started = CountDownLatch(1)
        val finishReading = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val published = AtomicBoolean(true)
        val oldRead = thread(name = "delayed-player-getter") {
            val ticket = gate.readTicket()
            started.countDown()
            check(finishReading.await(3, TimeUnit.SECONDS))
            published.set(gate.publishIfCurrent(ticket, stillCurrent = { true }) {
                events += "stale-position:30000"
            })
        }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        gate.acceptSeek { events += "explicit-seek:77000" }
        finishReading.countDown()
        oldRead.join(3_000L)
        assertFalse(oldRead.isAlive)
        assertFalse(published.get())
        assertEquals(listOf("explicit-seek:77000"), events.toList())
    }

    @Test fun switchingTheSourceOrPausingInvalidatesPendingRead() {
        val gate = ApplePlaybackClockEventGate()
        val first = gate.readTicket()
        gate.invalidate()
        assertFalse(gate.publishIfCurrent(first, stillCurrent = { true }) { })
        val next = gate.readTicket()
        assertFalse(gate.publishIfCurrent(next, stillCurrent = { false }) { })
        assertTrue(gate.publishIfCurrent(next, stillCurrent = { true }) { })
    }

    @Test fun rapidSeeksAndSamplesHaveStrictOrderEvenWithinOneMillisecond() {
        val gate = ApplePlaybackClockEventGate()
        val sequences = mutableListOf<Long>()
        gate.acceptSeek { sequences += it }
        gate.acceptSeek { sequences += it }
        val read = gate.readTicket()
        assertTrue(gate.publishIfCurrent(read, stillCurrent = { true }) {
            sequences += it
        })
        assertEquals(3, sequences.size)
        assertTrue(sequences[0] < sequences[1])
        assertTrue(sequences[1] < sequences[2])
    }

    @Test fun completedSeekIsDeliveredAfterAnAlreadyCommittedPositionPublication() {
        val gate = ApplePlaybackClockEventGate()
        val deliveringPosition = CountDownLatch(1)
        val releasePosition = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ticket = gate.readTicket()
        val oldDelivery = thread(name = "position-bridge") {
            gate.publishIfCurrent(ticket, stillCurrent = { true }) {
                deliveringPosition.countDown()
                check(releasePosition.await(3, TimeUnit.SECONDS))
                events += "position:30000"
            }
        }
        assertTrue(deliveringPosition.await(3, TimeUnit.SECONDS))
        val seekStarted = CountDownLatch(1)
        val seekDelivered = CountDownLatch(1)
        val explicitSeek = thread(name = "seek-bridge") {
            seekStarted.countDown()
            gate.acceptSeek { events += "onSeekTo:77000" }
            seekDelivered.countDown()
        }
        assertTrue(seekStarted.await(3, TimeUnit.SECONDS))
        assertFalse(seekDelivered.await(50, TimeUnit.MILLISECONDS))
        releasePosition.countDown()
        oldDelivery.join(3_000L)
        explicitSeek.join(3_000L)
        assertFalse(oldDelivery.isAlive)
        assertFalse(explicitSeek.isAlive)
        assertEquals(listOf("position:30000", "onSeekTo:77000"), events.toList())
    }
}
