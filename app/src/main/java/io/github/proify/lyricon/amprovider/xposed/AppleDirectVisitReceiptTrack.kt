package io.github.proify.lyricon.amprovider.xposed

/**
 * A source-visit identity, carried with the Binder request and its eventual response.
 * Song ID alone cannot distinguish the first and second visits in an A -> B -> A queue.
 */
internal class AppleDirectVisitReceiptTrack(
    // Distinct Apple Music processes must not reuse an old SystemUI cached receipt identity.
    initialSequence: Long = java.security.SecureRandom().nextLong().ushr(2).coerceAtLeast(1L),
) {
    private var currentSongId: String? = null
    private var currentVisit = initialSequence

    @Synchronized fun select(songId: String?): Long {
        val nextId = songId?.takeIf(String::isNotBlank)
        if (nextId != currentSongId) {
            currentSongId = nextId
            currentVisit++
        }
        return currentVisit
    }

    @Synchronized fun isCurrent(visit: Long, songId: String?): Boolean =
        visit > 0L && visit == currentVisit &&
            !songId.isNullOrBlank() && songId == currentSongId

    @Synchronized fun isCurrentClear(visit: Long, songId: String?): Boolean =
        visit > 0L && visit == currentVisit && currentSongId != null &&
            (songId == null || songId == currentSongId)

    /** Keep the arrival check and issuing downstream decode tickets in one visit boundary. */
    @Synchronized fun withCurrentVisit(visit: Long, action: () -> Unit): Boolean {
        if (visit <= 0L || visit != currentVisit || currentSongId == null) return false
        action()
        return true
    }

    @Synchronized fun withCurrentClear(visit: Long, songId: String?, action: () -> Unit): Boolean {
        if (!isCurrentClear(visit, songId)) return false
        action()
        return true
    }
}
