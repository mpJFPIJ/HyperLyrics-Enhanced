package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

/**
 * Apple-owned ViewModels can be reused across multiple outstanding loads. A native callback
 * carries the song/queue but not the originating invocation. Never assign the callback to
 * the newest load merely because it arrived during that playback visit.
 *
 * The same queue item can be revisited (A1 -> B -> A2). If two different visits on one
 * ViewModel could have produced a result, neither may write the cross-process song cache.
 * Host UI remains responsible for its original rendering when provenance is ambiguous.
 */
internal class AppleNativeLyricsVisitRequestTrack {
    sealed interface Origin {
        data class Verified(
            val songId: String,
            val queueId: Long?,
            val visitEpoch: Long,
        ) : Origin

        data object Ambiguous : Origin
        data object Unknown : Origin
    }

    private data class Key(val songId: String, val queueId: Long?)
    private class State {
        var loadCount = 0L
        val pending = mutableListOf<Origin.Verified>()
        val firstVisitByKey = mutableMapOf<Key, Long>()
        val firstVisitBySong = mutableMapOf<String, Long>()
        val ambiguousKeys = mutableSetOf<Key>()
        val ambiguousUnknownQueueSongs = mutableSetOf<String>()
        var saturated = false

        /** Once capacity is reached, fail closed forever for this host ViewModel. */
        fun saturate() {
            saturated = true
            pending.clear()
            firstVisitByKey.clear()
            firstVisitBySong.clear()
            ambiguousKeys.clear()
            ambiguousUnknownQueueSongs.clear()
        }
    }

    private val requests = WeakIdentityMap<Any, State>()

    private companion object {
        const val MAX_RECORDED_REQUEST_KEYS = 256
    }

    @Synchronized fun record(
        viewModel: Any,
        songId: String,
        queueId: Long?,
        visitEpoch: Long,
    ) {
        if (songId.isBlank()) return
        val normalizedQueue = queueId?.takeIf { it > 0L }
        val state = requests[viewModel] ?: State().also {
            requests[viewModel] = it
        }
        if (state.saturated) return
        state.loadCount++
        val key = Key(songId, normalizedQueue)
        if ((key !in state.firstVisitByKey &&
                state.firstVisitByKey.size >= MAX_RECORDED_REQUEST_KEYS) ||
            state.pending.size >= MAX_RECORDED_REQUEST_KEYS
        ) {
            state.saturate()
            return
        }
        val previousVisitForKey = state.firstVisitByKey.putIfAbsent(key, visitEpoch)
        if (previousVisitForKey != null && previousVisitForKey != visitEpoch) {
            state.ambiguousKeys.add(key)
        }
        val previousVisitForSong = state.firstVisitBySong.putIfAbsent(songId, visitEpoch)
        if (previousVisitForSong != null && previousVisitForSong != visitEpoch) {
            state.ambiguousUnknownQueueSongs.add(songId)
        }
        if (state.pending.none {
                it.songId == songId && it.queueId == normalizedQueue &&
                    it.visitEpoch == visitEpoch
            }
        ) {
            state.pending.add(Origin.Verified(songId, normalizedQueue, visitEpoch))
        }
    }

    @Synchronized fun claim(
        viewModel: Any?,
        resultSongId: String?,
        resultQueueId: Long?,
    ): Origin {
        if (viewModel == null || resultSongId.isNullOrBlank()) return Origin.Unknown
        val state = requests[viewModel] ?: return Origin.Unknown
        if (state.saturated) return Origin.Ambiguous
        val normalizedQueue = resultQueueId?.takeIf { it > 0L }
        if (normalizedQueue == null) {
            if (resultSongId in state.ambiguousUnknownQueueSongs) return Origin.Ambiguous
        } else if (Key(resultSongId, normalizedQueue) in state.ambiguousKeys ||
            (Key(resultSongId, null) in state.firstVisitByKey &&
                resultSongId in state.ambiguousUnknownQueueSongs)
        ) {
            return Origin.Ambiguous
        }
        val candidates = state.pending.filter { request ->
            request.songId == resultSongId &&
                (normalizedQueue == null || request.queueId == null ||
                    normalizedQueue == request.queueId)
        }
        if (candidates.isEmpty()) return Origin.Unknown
        // Matching only by the current song or native queue is insufficient: the exact
        // same queue item can still be in flight from two different playback visits.
        if (candidates.map(Origin.Verified::visitEpoch).distinct().size != 1) {
            return Origin.Ambiguous
        }
        // Keep the ticket for repeated build/LiveData callbacks in this visit.
        // More importantly, it must not disappear and let an A1 replay borrow A2 later.
        return candidates.singleOrNull() ?: Origin.Ambiguous
    }

    /** An error/null payload has no embedded song ID. The observer's captured song ID is
     * not evidence if the ViewModel has ever been reused for another load. */
    @Synchronized fun claimPointerless(viewModel: Any?, observerSongId: String?): Origin {
        if (viewModel == null) return Origin.Unknown
        val state = requests[viewModel] ?: return Origin.Unknown
        if (state.saturated || state.loadCount != 1L) return Origin.Ambiguous
        return claim(viewModel, observerSongId, null)
    }
}
