package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleKoreanTranslationForegroundPolicyTest {
    @Test fun onlyActiveMeaningMayOverlayNativeKaraokeGradient() {
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(
            9_999, 10_000, 13_000))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(
            10_001, 10_000, 13_000))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(
            13_001, 10_000, 13_000))
    }

    @Test fun recycledHolderCannotPaintOldMeaningBetweenBindAndRefresh() {
        val context = AppleTranslationLineContext(
            "song-a", "同じ", 1_000L, 2_000L, "같음", 3L,
        )
        var acceptedGeneration = 10L
        var holderGeneration = 10L
        fun canPaint() = AppleTranslationPaintOwnershipPolicy.canPaint(
            running = true,
            controlledTextsIntact = true,
            registered = true,
            acceptedGeneration = acceptedGeneration,
            holderGeneration = holderGeneration,
            context = context,
            presentedSongId = "song-a",
            presentedRevision = 3L,
            indexedSongId = "song-a",
            indexedRevision = 3L,
        )
        assertTrue(canPaint())

        // The host can begin a new bind while the old Korean text object is
        // still identical. A draw before the next refresh must not borrow it.
        holderGeneration++
        assertFalse(canPaint())

        // A verified same-holder/same-line rebind reauthorizes the retained
        // painter instead of permanently blanking an unchanged chorus.
        acceptedGeneration = holderGeneration
        assertTrue(canPaint())
    }

    @Test fun songRevisionOrIndexChangeRevokesPreviouslyWhiteMeaning() {
        val context = AppleTranslationLineContext(
            "song-a", "歌", 1_000L, 2_000L, "노래", 3L,
        )
        fun canPaint(
            presentedSong: String? = "song-a",
            presentedRevision: Long = 3L,
            indexedSong: String? = "song-a",
            indexedRevision: Long = 3L,
            registered: Boolean = true,
            intact: Boolean = true,
            running: Boolean = true,
        ) = AppleTranslationPaintOwnershipPolicy.canPaint(
            running = running,
            controlledTextsIntact = intact,
            registered = registered,
            acceptedGeneration = 7L,
            holderGeneration = 7L,
            context = context,
            presentedSongId = presentedSong,
            presentedRevision = presentedRevision,
            indexedSongId = indexedSong,
            indexedRevision = indexedRevision,
        )
        assertTrue(canPaint())
        assertFalse(canPaint(presentedSong = "song-b"))
        assertFalse(canPaint(presentedRevision = 4L))
        assertFalse(canPaint(indexedSong = "song-b"))
        assertFalse(canPaint(indexedRevision = 4L))
        assertFalse(canPaint(registered = false))
        assertFalse(canPaint(intact = false))
        assertFalse(canPaint(running = false))
        assertFalse(AppleTranslationPaintOwnershipPolicy.canPaint(
            running = true,
            controlledTextsIntact = true,
            registered = true,
            acceptedGeneration = 0L,
            holderGeneration = null,
            context = context,
            presentedSongId = "song-a",
            presentedRevision = 3L,
            indexedSongId = "song-a",
            indexedRevision = 3L,
        ))
    }
}
