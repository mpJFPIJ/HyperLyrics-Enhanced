package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCachedSongCatalogTest {
    @Test fun lyricRowBoundariesArePartOfTheCacheGroupIdentity() {
        val combined = AiCachedSongCatalog.groupId("song", "artist", listOf("ありがとう\n1:사랑해"))
        val split = AiCachedSongCatalog.groupId("song", "artist", listOf("ありがとう", "사랑해"))
        assertNotEquals(combined, split)
    }

    @Test fun failedPhoneticsRepairUsesShortCooldownWithoutReleasingNewerVisit() {
        val scope = AiCacheScope("song", setOf("phonetics-key"))
        val ticket = AiCacheWriteTicket(scope, 0L, 1)
        val attempts = AiPhoneticsRepairAttempts(10L * 60_000L)
        assertTrue(attempts.claim("phonetics-key", ticket, 1_000L))
        attempts.markFailed("phonetics-key", ticket, 1_000L)
        assertFalse(attempts.claim("phonetics-key", ticket, 120_999L))
        assertTrue(attempts.claim("phonetics-key", ticket, 121_000L))
        attempts.markSucceeded("phonetics-key", ticket, 121_000L, 121_000L)
        // A delayed failure callback from the first attempt cannot undo this claim.
        attempts.markFailed("phonetics-key", ticket, 1_000L)
        assertFalse(attempts.claim("phonetics-key", ticket, 121_001L))
        assertTrue(attempts.claim("phonetics-key", ticket, 721_000L))
    }

    @Test fun phoneticsRepairRemainsSingleFlightUntilTheFirstAttemptFinishes() {
        val scope = AiCacheScope("slow-song", setOf("phonetics"))
        val ticket = AiCacheWriteTicket(scope, 0L, 2)
        val attempts = AiPhoneticsRepairAttempts(10L * 60_000L)
        assertTrue(attempts.claim("phonetics", ticket, 100L))
        assertFalse(attempts.claim("phonetics", ticket, 11L * 60_000L))
        attempts.markFailed("phonetics", ticket, 100L, 11L * 60_000L)
        assertFalse(attempts.claim("phonetics", ticket, 13L * 60_000L - 1L))
        assertTrue(attempts.claim("phonetics", ticket, 13L * 60_000L))
    }

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
            AiCachedSongCatalog.deletionScope(result.first().groupId, listOf(
                AiCachedSongRow("translation-a", groupA, "My Song", "Singer", 100L),
                AiCachedSongRow("pronunciation-a", groupA, "My Song", "Singer", 300L),
                AiCachedSongRow("translation-b", groupB, "Another Song", "Singer", 200L),
            )).keys)
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
            AiCachedSongCatalog.deletionScope(songs.first().groupId, rows).keys)
    }

    @Test fun migratedLegacyTranslationAndEnrichedVariantDeleteTogetherAfterBackfill() {
        val group = AiCachedSongCatalog.groupId("Song", "Singer", listOf("original lyrics"))
        val rows = listOf(
            AiCachedSongRow("old-translation-key", group, "Song", "Singer", 100L),
            AiCachedSongRow("new-pronunciation-key", group, "Song", "Singer", 200L),
        )
        assertEquals(setOf("old-translation-key", "new-pronunciation-key"),
            AiCachedSongCatalog.deletionScope(group, rows).keys)
        assertEquals(1, AiCachedSongCatalog.groupRows(rows).size)
    }

    @Test fun deletingDisplayedLegacyRowAfterBackfillIncludesItsCanonicalVariants() {
        val legacy = AiCachedSongRow("old", null, null, null, 10L)
        val displayed = AiCachedSongCatalog.groupRows(listOf(legacy)).single()
        val currentRows = listOf(
            legacy.copy(songGroupId = "song-a"),
            AiCachedSongRow("reading", "song-a", "A", null, 20L),
            AiCachedSongRow("other", "song-b", "B", null, 30L),
        )

        val deleted = AiCachedSongCatalog.deletionScope(displayed.groupId, currentRows)
        assertEquals("song-a", deleted.groupId)
        assertEquals(setOf("old", "reading"), deleted.keys)
    }

    @Test fun deletingLegacyInputFencesCanonicalRepairAndAllowsFreshVisit() {
        val legacy = AiCachedSongRow("old", null, null, null, 10L)
        val displayed = AiCachedSongCatalog.groupRows(listOf(legacy)).single()
        val epoch = AiCacheGroupEpochs()
        val repair = AiCacheScope("song-a", setOf("old", "enriched", "reading"))
        val other = AiCacheScope("song-b", setOf("unrelated"))
        val started = epoch.current(repair)
        val otherStarted = epoch.current(other)

        epoch.advance(AiCachedSongCatalog.deletionScope(displayed.groupId, listOf(legacy)))
        val fresh = epoch.current(repair)
        assertNotEquals(started, fresh)
        assertEquals(otherStarted, epoch.current(other))

        // An intervening deletion on a different song leaves fresh work valid.
        epoch.advance(other)
        assertEquals(fresh, epoch.current(repair))
        // A later canonical deletion must still supersede the legacy key tombstone.
        epoch.advance(AiCacheScope("song-a", emptySet()))
        assertNotEquals(fresh, epoch.current(repair))
    }

    @Test fun obsoleteRepairDoesNotConsumeTheNewVisitsRetryWindow() {
        val epoch = AiCacheGroupEpochs()
        val scope = AiCacheScope("song", setOf("old", "enriched"))
        val old = AiCacheWriteTicket(scope, epoch.current(scope), 3)
        val attempts = AiPhoneticsRepairAttempts(1000L)
        assertTrue(attempts.claim("enriched", old, 100L))
        epoch.advance(AiCacheScope("legacy:old", setOf("old")))
        val fresh = old.copy(epoch = epoch.current(scope))

        assertTrue(attempts.claim("enriched", fresh, 110L))
        attempts.markSucceeded("enriched", fresh, 110L, 110L)
        assertFalse(attempts.claim("enriched", old, 120L))
        assertFalse(attempts.claim("enriched", fresh, 130L))
        assertTrue(attempts.claim("enriched", fresh, 1110L))
    }

    @Test fun deletionEpochRejectsStaleRepairWithoutBlockingOtherSongs() {
        val epochs = AiCacheGroupEpochs()
        val songA = AiCacheScope("song-a", setOf("a"))
        val songB = AiCacheScope("song-b", setOf("b"))
        val a = AiCacheWriteTicket(songA, epochs.current(songA), 8)
        val b = AiCacheWriteTicket(songB, epochs.current(songB), 8)
        epochs.advance(songA)

        assertFalse(a.isCurrent(8, epochs.current(songA)))
        assertTrue(b.isCurrent(8, epochs.current(songB)))
    }
}
