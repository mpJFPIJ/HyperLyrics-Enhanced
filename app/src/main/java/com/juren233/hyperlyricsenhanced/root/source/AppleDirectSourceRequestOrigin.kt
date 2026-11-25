package com.juren233.hyperlyricsenhanced.root.source

internal data class AppleDirectSourceVisit(val songId: String?, val visitId: Long) {
    fun forSong(requestedSongId: String?): Long =
        visitId.takeIf { requestedSongId != null && songId == requestedSongId } ?: 0L
}

/** A response's origin is fixed when its asynchronous source request starts. */
internal class AppleDirectSourceRequestOrigin {
    private data class Request(val generation: Int, val songId: String, val visitId: Long)
    private var latest: Request? = null

    @Synchronized fun remember(generation: Int, songId: String?, visitId: Long) {
        latest = if (songId.isNullOrBlank()) null
            else Request(generation, songId, visitId)
    }

    @Synchronized fun visitFor(generation: Int, songId: String?): Long? =
        latest?.takeIf {
            it.generation == generation && it.songId == songId && it.visitId > 0L
        }?.visitId

    @Synchronized fun needsProvenVisit(songId: String?): Boolean =
        latest?.let { it.songId == songId && it.visitId <= 0L } == true
}
