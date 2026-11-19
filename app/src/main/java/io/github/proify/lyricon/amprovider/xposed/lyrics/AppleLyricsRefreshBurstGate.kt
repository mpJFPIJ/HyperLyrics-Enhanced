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
