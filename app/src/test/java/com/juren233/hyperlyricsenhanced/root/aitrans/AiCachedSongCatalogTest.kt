package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCachedSongCatalogTest {
    @Test fun groupsTranslationAndPronunciationVariantsWithoutDeletingAnotherSong() {
        val groupA = AiCachedSongCatalog.groupId("My Song", "Singer", listOf("line one"))
        val groupB = AiCachedSongCatalog.groupId("Another Song", "Singer", listOf("line two"))
        val result = AiCachedSongCatalog.groupRows(listOf(
            AiCachedSongRow("translation-a", groupA, "My Song", "Singer", 100L),
            AiCachedSongRow("pronunciation-a", groupA, "My Song", "Singer", 300L),
            AiCachedSongRow("translation-b", groupB, "Another Song", "Singer", 200L),
        ))

        assertEquals(2, result.size)
        assertEquals("My Song", result.first().title)
        assertEquals(2, result.first().variantCount)
        assertEquals(setOf("translation-a", "pronunciation-a"),
            AiCachedSongCatalog.keysForGroup(result.first().groupId, listOf(
                AiCachedSongRow("translation-a", groupA, "My Song", "Singer", 100L),
                AiCachedSongRow("pronunciation-a", groupA, "My Song", "Singer", 300L),
                AiCachedSongRow("translation-b", groupB, "Another Song", "Singer", 200L),
            )))
        assertFalse(result.first().isLegacy)
    }

    @Test fun separatesDifferentLyricsEvenForIdenticalTitleAndArtist() {
        val original = AiCachedSongCatalog.groupId("Same", "Singer", listOf("first line"))
        val different = AiCachedSongCatalog.groupId("Same", "Singer", listOf("different line"))
        assertNotEquals(original, different)
        assertEquals(original, AiCachedSongCatalog.groupId("Same", "Singer", listOf("first line")))
    }

    @Test fun oldEntriesWithoutMetadataRemainIndividuallyRemovable() {
        val rows = listOf(
            AiCachedSongRow("legacy-cache-one", null, null, null, 10L),
            AiCachedSongRow("legacy-cache-two", null, null, null, 20L),
        )
        val songs = AiCachedSongCatalog.groupRows(rows)

        assertEquals(2, songs.size)
        assertTrue(songs.all { it.isLegacy })
        assertEquals(setOf("legacy-cache-two"),
            AiCachedSongCatalog.keysForGroup(songs.first().groupId, rows))
    }

    @Test fun migratedLegacyTranslationAndEnrichedVariantDeleteTogetherAfterBackfill() {
        val group = AiCachedSongCatalog.groupId("Song", "Singer", listOf("original lyrics"))
        val rows = listOf(
            AiCachedSongRow("old-translation-key", group, "Song", "Singer", 100L),
            AiCachedSongRow("new-pronunciation-key", group, "Song", "Singer", 200L),
        )
        assertEquals(setOf("old-translation-key", "new-pronunciation-key"),
            AiCachedSongCatalog.keysForGroup(group, rows))
        assertEquals(1, AiCachedSongCatalog.groupRows(rows).size)
    }

    @Test fun deletionEpochRejectsStaleRepairWithoutBlockingOtherSongs() {
        val epochs = AiCacheGroupEpochs()
        val songA = epochs.current("song-a")
        val songB = epochs.current("song-b")
        epochs.advance("song-a")

        assertFalse(epochs.isCurrent("song-a", songA))
        assertTrue(epochs.isCurrent("song-b", songB))
        assertTrue(epochs.isCurrent("song-a", epochs.current("song-a")))
    }
}
