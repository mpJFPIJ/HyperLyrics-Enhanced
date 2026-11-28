package io.github.proify.lyricon.amprovider.xposed

/**
 * Orders incoming supplement payloads by arrival, not by the completion of their decoding.
 *
 * Both a clear and a newer payload revoke previously queued decodes. The commit is performed
 * under the same monitor as revocation so an in-flight store/disk write cannot race a clear.
 */
internal class AppleSupplementReceiptTrack {
    private var generation = 0L
    private var clearGeneration = 0L
    private var playbackVisitGeneration = 0L
    private var observedSongId: String? = null

    @Synchronized
    fun observeSong(songId: String?) {
        val resolved = songId?.takeIf(String::isNotBlank)
        if (resolved != observedSongId) {
            observedSongId = resolved
            playbackVisitGeneration++
            invalidate()
        }
    }

    /** A cache clear revokes supplement decodes but does not switch the playing song. */
    @Synchronized fun visitEpoch(): Long = playbackVisitGeneration

    @Synchronized
    fun issue(): Long = ++generation

    /** The visit check runs under the same lock as ticket issuance and disk commits. */
    @Synchronized
    fun issueIf(guard: () -> Boolean): Long? = if (guard()) ++generation else null

    @Synchronized
    fun invalidate() {
        generation++
        clearGeneration++
    }

    @Synchronized
    fun clearIfCurrent(guard: () -> Boolean, clear: () -> Unit): Boolean {
        if (!guard()) return false
        invalidate()
        clear()
        return true
    }

    /** Online translation can arrive without owning the base-lyrics delivery order. */
    @Synchronized
    fun clearEpoch(): Long = clearGeneration

    @Synchronized
    fun commitIfCurrent(
        ticket: Long,
        payloadSongId: String?,
        currentSongId: String?,
        guard: () -> Boolean = { true },
        apply: () -> Unit,
    ): Boolean {
        if (ticket != generation || payloadSongId.isNullOrBlank() ||
            payloadSongId != currentSongId || !guard()
        ) {
            return false
        }
        apply()
        return true
    }

    @Synchronized
    fun commitIfNotCleared(
        epoch: Long,
        payloadSongId: String?,
        currentSongId: String?,
        apply: () -> Unit,
    ): Boolean {
        if (epoch != clearGeneration || payloadSongId.isNullOrBlank() || payloadSongId != currentSongId) {
            return false
        }
        apply()
        return true
    }
}
