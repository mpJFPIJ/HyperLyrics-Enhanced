package io.github.proify.lyricon.amprovider.xposed.hooks

/**
 * Serializes completed player reads with Exo seeks and source changes.
 *
 * A read ticket is captured BEFORE calling Apple's position getter. If a seek,
 * pause, or player replacement arrives while that getter is in flight, the
 * ticket cannot publish its old value, even if it finishes much later.
 *
 * The supplied publication action runs under the same short critical section
 * as the explicit seek action so a remote position tick cannot arrive after
 * the seek and silently rewind an attached lyric client. These actions should
 * perform only bounded position/bridge publication, never a network request.
 */
internal class ApplePlaybackClockEventGate {
    private val lock = Any()
    private var generation = 0L
    private var sequence = 0L

    fun readTicket(): Long = synchronized(lock) { generation }

    fun invalidate() {
        synchronized(lock) { generation++ }
    }

    fun acceptSeek(
        stillCurrent: () -> Boolean = { true },
        publish: (sequence: Long) -> Unit,
    ): Boolean = synchronized(lock) {
        if (!stillCurrent()) return@synchronized false
        generation++
        publish(++sequence)
        true
    }

    fun publishIfCurrent(
        ticket: Long,
        stillCurrent: () -> Boolean,
        publish: (sequence: Long) -> Unit,
    ): Boolean = synchronized(lock) {
        if (ticket != generation || !stillCurrent()) return@synchronized false
        publish(++sequence)
        true
    }
}
