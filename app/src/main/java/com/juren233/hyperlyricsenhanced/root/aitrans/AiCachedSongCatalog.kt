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

    fun keysForGroup(groupId: String, rows: List<AiCachedSongRow>): Set<String> =
        rows.asSequence().filter { groupOf(it) == groupId }.map { it.cacheKey }.toSet()

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

/** A per-song write fence: old network callbacks cannot revive deleted cache entries. */
internal class AiCacheGroupEpochs {
    private val values = mutableMapOf<String, Long>()

    @Synchronized fun current(groupId: String): Long = values[groupId] ?: 0L

    @Synchronized fun advance(groupId: String): Long {
        val next = current(groupId) + 1L
        values[groupId] = next
        return next
    }

    @Synchronized fun isCurrent(groupId: String, expected: Long): Boolean =
        current(groupId) == expected
}
