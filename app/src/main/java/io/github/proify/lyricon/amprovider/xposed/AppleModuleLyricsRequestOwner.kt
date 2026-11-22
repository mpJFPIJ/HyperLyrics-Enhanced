package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

/**
 * Associates each module-created lyrics ViewModel with the exact request that created it.
 * A single mutable `requestedMediaId` on a reused ViewModel cannot identify A1 versus A2.
 * Weak keys ensure completed ViewModels are not kept alive by the receipt registry.
 */
internal class AppleModuleLyricsRequestOwner {
    internal data class Receipt(
        val songId: String,
        val queueId: Long,
        val visitEpoch: Long,
        val serial: Long,
    )

    private val requests = WeakIdentityMap<Any, Receipt>()
    private var latestSerial = 0L

    @Synchronized fun register(
        viewModel: Any,
        songId: String,
        queueId: Long,
        visitEpoch: Long,
    ) {
        requests[viewModel] = Receipt(songId, queueId, visitEpoch, ++latestSerial)
    }

    @Synchronized fun owns(viewModel: Any?): Boolean =
        viewModel != null && requests[viewModel] != null

    @Synchronized fun requestedSongId(viewModel: Any?): String? =
        viewModel?.let { requests[it]?.songId }

    @Synchronized fun receipt(viewModel: Any?): Receipt? =
        viewModel?.let { requests[it] }

    @Synchronized fun isCurrent(
        viewModel: Any?,
        resultSongId: String?,
        activeVisitEpoch: Long,
        activeQueueId: Long?,
    ): Boolean {
        return isCurrentReceipt(receipt(viewModel), resultSongId, activeVisitEpoch, activeQueueId)
    }

    @Synchronized fun isCurrentReceipt(
        request: Receipt?,
        resultSongId: String?,
        activeVisitEpoch: Long,
        activeQueueId: Long?,
    ): Boolean {
        request ?: return false
        if (request.serial != latestSerial || request.songId != resultSongId ||
            request.visitEpoch != activeVisitEpoch
        ) return false
        val requestedQueueId = request.queueId.takeIf { it > 0L }
        val activeQueue = activeQueueId?.takeIf { it > 0L }
        return requestedQueueId == null || activeQueue == null || requestedQueueId == activeQueue
    }
}
