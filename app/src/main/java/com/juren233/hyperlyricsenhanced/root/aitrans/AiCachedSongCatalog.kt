package com.juren233.hyperlyricsenhanced.root.aitrans

import java.security.MessageDigest

/** Display-only metadata; the translation text and API credentials are not exposed. */
data class AiCachedSong(
    val groupId: String,
    val title: String?,
    val artist: String?,
    val updatedAt: Long,
    val variantCount: Int,
    val isLegacy: Boolean,
)

internal data class AiCachedSongRow(
    val cacheKey: String,
    val songGroupId: String?,
    val title: String?,
    val artist: String?,
    val updatedAt: Long,
)

/** All keys a request can depend on, including the translation it might enrich. */
internal data class AiCacheScope(val groupId: String, val keys: Set<String>)

internal data class AiCacheWriteTicket(val scope: AiCacheScope, val epoch: Long, val generation: Int) {
    fun isCurrent(currentGeneration: Int, currentEpoch: Long): Boolean =
        generation == currentGeneration && epoch == currentEpoch
}

internal object AiCachedSongCatalog {
    /** Independent of provider song IDs and AI model, but distinct for different lyric bodies. */
    fun groupId(title: String?, artist: String?, lines: List<String>): String {
        val input = buildString {
            append("title=").appendLine(title.orEmpty())
            append("artist=").appendLine(artist.orEmpty())
            lines.forEachIndexed { index, line -> append(index).append(':').appendLine(line) }
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun groupOf(row: AiCachedSongRow): String =
        row.songGroupId ?: "legacy:${row.cacheKey}"

    /** A legacy UI selection keeps identifying the same row after metadata backfill. */
    fun deletionScope(groupId: String, rows: List<AiCachedSongRow>): AiCacheScope {
        val canonicalGroup = if (groupId.startsWith("legacy:")) {
            val key = groupId.removePrefix("legacy:")
            rows.firstOrNull { it.cacheKey == key }?.songGroupId ?: groupId
        } else groupId
        val keys = rows.asSequence().filter { groupOf(it) == canonicalGroup }.map { it.cacheKey }.toSet()
        return AiCacheScope(canonicalGroup, keys)
    }

    fun groupRows(rows: List<AiCachedSongRow>): List<AiCachedSong> =
        rows.groupBy(::groupOf).map { (id, variants) ->
            val newest = variants.maxBy { it.updatedAt }
            AiCachedSong(
                groupId = id,
                title = newest.title,
                artist = newest.artist,
                updatedAt = newest.updatedAt,
                variantCount = variants.size,
                isLegacy = newest.songGroupId == null,
            )
        }.sortedWith(compareByDescending<AiCachedSong> { it.updatedAt }.thenBy { it.groupId })
}

/** Only deleted identities need tombstones. One clock orders key and group deletions. */
internal class AiCacheGroupEpochs {
    private var sequence = 0L
    private val groupEpochs = mutableMapOf<String, Long>()
    private val keyEpochs = mutableMapOf<String, Long>()

    fun current(scope: AiCacheScope): Long = maxOf(
        groupEpochs[scope.groupId] ?: 0L,
        scope.keys.maxOfOrNull { keyEpochs[it] ?: 0L } ?: 0L,
    )

    fun advance(scope: AiCacheScope) {
        val epoch = ++sequence
        groupEpochs[scope.groupId] = epoch
        scope.keys.forEach { keyEpochs[it] = epoch }
    }
}
