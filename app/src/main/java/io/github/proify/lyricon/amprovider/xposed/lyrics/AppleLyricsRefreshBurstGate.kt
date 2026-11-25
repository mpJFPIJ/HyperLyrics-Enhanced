package io.github.proify.lyricon.amprovider.xposed

/**
 * Identifies the presentation work that a full native adapter rebind will refresh.
 * A different native generation or visibility request must never be discarded.
 */
internal data class AppleLyricsRefreshKey(
    val songId: String?,
    val revision: Long?,
    val syncDisplayFlags: Boolean,
    val presentationGeneration: Long,
)

/**
 * Suppress only identical refreshes that are already scheduled.
 * Completing one request must not release an unrelated presentation's work.
 */
internal class AppleLyricsRefreshBurstGate {
    private val pending = HashSet<AppleLyricsRefreshKey>()

    @Synchronized fun begin(key: AppleLyricsRefreshKey): Boolean =
        pending.add(key)

    @Synchronized fun end(key: AppleLyricsRefreshKey) {
        pending.remove(key)
    }
}

/** Collapses different online-translation revisions into one upcoming UI frame.
 * A different song or presentation generation is a separate request, so an
 * obsolete callback cannot consume the next song's pending refresh. */
internal class AppleLyricsFrameRefreshQueue {
    private data class Group(val songId: String?, val presentationGeneration: Long)
    private val pending = LinkedHashMap<Group, AppleLyricsRefreshKey>()

    @Synchronized fun offer(request: AppleLyricsRefreshKey): Boolean {
        val group = Group(request.songId, request.presentationGeneration)
        val previous = pending[group]
        pending[group] = if (previous == null) request else request.copy(
            revision = listOfNotNull(previous.revision, request.revision).maxOrNull(),
            syncDisplayFlags = previous.syncDisplayFlags || request.syncDisplayFlags,
        )
        return previous == null
    }

    @Synchronized fun consume(scheduledRequest: AppleLyricsRefreshKey): AppleLyricsRefreshKey? =
        pending.remove(Group(scheduledRequest.songId, scheduledRequest.presentationGeneration))

    @Synchronized fun peek(scheduledRequest: AppleLyricsRefreshKey): AppleLyricsRefreshKey? =
        pending[Group(scheduledRequest.songId, scheduledRequest.presentationGeneration)]
}

internal object AppleLyricsRefreshRetryPolicy {
    const val MAX_LAYOUT_RETRIES = 8

    fun shouldRetry(attempts: Int): Boolean = attempts < MAX_LAYOUT_RETRIES
}
