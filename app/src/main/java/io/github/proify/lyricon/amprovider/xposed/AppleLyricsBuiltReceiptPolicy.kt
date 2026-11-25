package io.github.proify.lyricon.amprovider.xposed

/**
 * A delayed native build result belongs to its originating queue item and playback visit.
 * Comparing song IDs alone cannot separate A1 from A2 in an A -> B -> A queue.
 */
internal fun shouldAcceptAppleLyricsBuiltReceipt(
    builtSongId: String?,
    activeSongId: String?,
    scheduledVisitEpoch: Long,
    activeVisitEpoch: Long,
    resultQueueId: Long?,
    activeQueueId: Long?,
    isModuleResult: Boolean,
    moduleRequestProven: Boolean,
    nativeRequestProven: Boolean = false,
): Boolean {
    if (builtSongId.isNullOrBlank() || builtSongId != activeSongId ||
        scheduledVisitEpoch != activeVisitEpoch
    ) return false
    val activeQueue = activeQueueId?.takeIf { it > 0L }
    val resultQueue = resultQueueId?.takeIf { it > 0L }
    if (activeQueue != null && resultQueue != null && activeQueue != resultQueue) return false
    if (isModuleResult) return moduleRequestProven
    // A matching queue ID cannot distinguish A1 from A2 when Apple revisits the same item.
    // Only an unambiguous ticket captured at loadLyrics entry may authorize native disk writes.
    return nativeRequestProven
}

/** Restore cold-start native publication only when there is no conflicting playback identity. */
internal fun shouldBootstrapNativePlaybackVisit(
    currentSongId: String?,
    builtSongId: String?,
    visibleSongId: String?,
    playbackSongId: String?,
    source: String,
    isModuleResult: Boolean,
    resultQueueId: Long?,
    activeQueueId: Long?,
    scheduledVisitEpoch: Long,
    activeVisitEpoch: Long,
    nativeRequestProven: Boolean = false,
): Boolean = currentSongId == null && source == "apple" && !isModuleResult &&
    !builtSongId.isNullOrBlank() && builtSongId == visibleSongId &&
    builtSongId == playbackSongId &&
    shouldAcceptAppleLyricsBuiltReceipt(
        builtSongId = builtSongId,
        activeSongId = playbackSongId,
        scheduledVisitEpoch = scheduledVisitEpoch,
        activeVisitEpoch = activeVisitEpoch,
        resultQueueId = resultQueueId,
        activeQueueId = activeQueueId,
        isModuleResult = false,
        moduleRequestProven = false,
        nativeRequestProven = nativeRequestProven,
    )

/** Reject only contradictions the host has positively observed; unknown is not stale proof. */
internal fun isClearlyStaleNativePresentation(
    nativeSongId: String?,
    activeSongId: String?,
    nativeQueueId: Long?,
    activeQueueId: Long?,
): Boolean {
    if (nativeSongId.isNullOrBlank() || activeSongId.isNullOrBlank()) return false
    if (nativeSongId != activeSongId) return true
    val nativeQueue = nativeQueueId?.takeIf { it > 0L } ?: return false
    val activeQueue = activeQueueId?.takeIf { it > 0L } ?: return false
    return nativeQueue != activeQueue
}

/** A nonnull native pointer is not an empty-lyrics response when its song cannot be read. */
internal fun canReportNativeLyricsPointerAvailability(
    nativeSong: Any?,
    nativeSongId: String?,
): Boolean = nativeSong != null && !nativeSongId.isNullOrBlank()

/** Only an entirely readable, zero-line native section vector proves "no lyrics". */
internal fun confirmedNativeSectionAvailability(
    sectionCount: Int?,
    lineCounts: List<Int?>,
): Boolean? {
    if (sectionCount == null || sectionCount < 0 || lineCounts.size != sectionCount) return null
    if (lineCounts.any { it != null && it > 0 }) return true
    if (lineCounts.any { it == null || it < 0 }) return null
    return false
}
