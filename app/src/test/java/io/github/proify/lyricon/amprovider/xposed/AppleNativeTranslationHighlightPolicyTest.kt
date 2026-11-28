package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativeTranslationHighlightPolicyTest {
    @Test fun detachedMeaningPainterParksForSameHolderReattachButRetiresWhenRevoked() {
        assertTrue(AppleTranslationDetachedPulsePolicy.mayPark(
            running = true, stillOwned = true, textIntact = true,
        ))
        assertFalse(AppleTranslationDetachedPulsePolicy.mayPark(
            running = false, stillOwned = true, textIntact = true,
        ))
        assertFalse(AppleTranslationDetachedPulsePolicy.mayPark(
            running = true, stillOwned = false, textIntact = true,
        ))
        assertFalse(AppleTranslationDetachedPulsePolicy.mayPark(
            running = true, stillOwned = true, textIntact = false,
        ))
        // Detachment does not renew the owner. A song/revision change is
        // checked again on reattach through the normal paint owner guard.
        assertFalse(AppleTranslationPaintOwnershipPolicy.canPaint(
            running = true, controlledTextsIntact = true, registered = true,
            acceptedGeneration = 4L, holderGeneration = 4L,
            context = AppleTranslationLineContext("old", "あ", 100L, 200L, "나", 7L),
            presentedSongId = "new", presentedRevision = 8L,
            indexedSongId = "new", indexedRevision = 8L,
        ))
    }

    @Test fun acceptedSeekWakesOnlyAnAttachedMeaningFromTheLiveSongAndRevision() {
        fun eligible(
            requestedSong: String? = "song-a",
            requestedRevision: Long = 7L,
            presentedSong: String? = "song-a",
            presentedRevision: Long = 7L,
            pulseSong: String = "song-a",
            pulseRevision: Long = 7L,
            attached: Boolean = true,
            running: Boolean = true,
        ) = AppleTranslationSeekWakePolicy.shouldWake(
            requestedSong, requestedRevision, presentedSong, presentedRevision,
            pulseSong, pulseRevision, attached, running,
        )

        assertTrue(eligible())
        assertFalse(eligible(requestedSong = null))
        assertFalse(eligible(requestedSong = ""))
        assertFalse(eligible(requestedSong = "song-b"))
        assertFalse(eligible(requestedRevision = 8L))
        assertFalse(eligible(presentedSong = "song-b"))
        assertFalse(eligible(presentedRevision = 8L))
        assertFalse(eligible(pulseSong = "song-b"))
        assertFalse(eligible(pulseRevision = 8L))
        assertFalse(eligible(attached = false))
        assertFalse(eligible(running = false))
    }

    @Test fun recyclerOwnerRecognizedAcrossHostModuleClassLoaders() {
        assertTrue(AppleTranslationRecyclerOwnerPolicy.isRecyclerViewHierarchy(
            listOf("com.apple.music.lyrics.NativeRecycler", "androidx.recyclerview.widget.RecyclerView",
                "android.view.ViewGroup"),
        ))
        assertFalse(AppleTranslationRecyclerOwnerPolicy.isRecyclerViewHierarchy(
            listOf("com.apple.music.lyrics.LineHolder", "android.widget.FrameLayout"),
        ))
    }

    @Test fun actualPlaybackClockRejectsStaleOrFutureNativeActiveRows() {
        val rows = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_100L),
            AppleTranslationFocusCandidate(5, 2_000L, 2_800L),
            AppleTranslationFocusCandidate(7, 3_000L, 3_900L),
        )
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(5), 1_600L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), null))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(9), 2_300L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, emptySet(), 2_300L))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(5), 2_900L))
    }

    @Test fun verifiedNativeRowWithoutMeaningMustDimAnOverlappingOlderMeaning() {
        val meaningRows = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_400L),
        )
        // Apple also timed row 5 [1800,2300), but its translation is blank,
        // so it deliberately has NO foreground painter in meaningRows.
        assertEquals(0, AppleTranslationFocusPolicy.select(meaningRows, setOf(5), 2_000L))
        assertNull(AppleTranslationFocusPolicy.select(
            meaningRows, setOf(5), 2_000L, verifiedNativeActiveRow = 5,
        ))
        assertEquals(0, AppleTranslationFocusPolicy.select(
            meaningRows, setOf(4), 1_600L, verifiedNativeActiveRow = 4,
        ))

        // A lagging native active-position set after an accepted seek is not
        // allowed to veto a later timed meaning. Its expired row 4 window
        // cannot independently confirm a verified native-active row.
        val seekRows = meaningRows + AppleTranslationFocusCandidate(6, 2_500L, 3_300L)
        assertEquals(1, AppleTranslationFocusPolicy.select(
            seekRows, setOf(4), 2_700L, verifiedNativeActiveRow = null,
        ))
        // The native row 5 expires: it is no longer evidence against an
        // overlapping valid meaning in a reverse-seek recovery.
        assertEquals(0, AppleTranslationFocusPolicy.select(
            meaningRows, setOf(5), 2_350L, verifiedNativeActiveRow = null,
        ))
    }

    @Test fun blankMeaningRowVetoRequiresSameAdapterSongRevisionAndNativeClock() {
        val windows = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        val nextAdapter = Any()
        windows.remember("song", 7L, adapter, 4, 1_000L, 2_400L)
        // Getter records the *source* clock even when its meaning is blank.
        windows.remember("song", 7L, adapter, 5, 1_800L, 2_300L)
        val onlyMeaning = listOf(AppleTranslationFocusCandidate(4, 1_000L, 2_400L))
        val verified = windows.focus("song", 7L, adapter, setOf(4, 5), 2_000L)
        assertEquals(5, verified)
        assertNull(AppleTranslationFocusPolicy.select(
            onlyMeaning, setOf(4, 5), 2_000L, verifiedNativeActiveRow = verified,
        ))
        // No unknown adapter or outgoing revision may be allowed to dim an
        // otherwise correctly timed meaning.
        assertNull(windows.focus("song", 7L, nextAdapter, setOf(5), 2_000L))
        assertNull(windows.focus("song", 8L, adapter, setOf(5), 2_000L))
        assertEquals(0, AppleTranslationFocusPolicy.select(
            onlyMeaning, setOf(4), 2_000L, verifiedNativeActiveRow = null,
        ))
        assertNull(windows.focus("song", 7L, adapter, setOf(5), 2_350L))
    }

    @Test fun unknownSecondNativeActiveRowMustNotPromoteOnlyMeaningOnEqualClock() {
        val windows = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        windows.remember("song", 2L, adapter, 10, 1_000L, 3_000L)
        windows.remember("song", 2L, adapter, 11, 1_000L, 2_500L)
        val onlyMeaning = listOf(AppleTranslationFocusCandidate(10, 1_000L, 3_000L))
        val overlap = setOf(10, 11)
        val ambiguous = windows.focus("song", 2L, adapter, overlap, 1_500L)
        assertNull(ambiguous)
        assertNull(AppleTranslationFocusPolicy.select(
            onlyMeaning, overlap, 1_500L, verifiedNativeActiveRow = ambiguous,
        ))
        // A seek can leave *every* host active position stale. Neither is a
        // corroborating current painter, so do not suppress a newer verified
        // meaning whose own timed source interval already matches playback.
        assertEquals(0, AppleTranslationFocusPolicy.select(
            onlyMeaning, setOf(3, 4), 1_500L,
        ))
        // Host's sole active native row is enough to corroborate that painter.
        assertEquals(0, AppleTranslationFocusPolicy.select(
            onlyMeaning, setOf(10), 1_500L,
        ))
        // After 11's genuine window ends, the clock itself uniquely resolves 10.
        assertEquals(10, windows.focus("song", 2L, adapter, overlap, 2_700L))
        assertEquals(0, AppleTranslationFocusPolicy.select(
            onlyMeaning, overlap, 2_700L, verifiedNativeActiveRow = 10,
        ))
    }

    @Test fun verifiedNativeSourceClockOutranksCompetingMeaningBeginTime() {
        val meanings = listOf(
            AppleTranslationFocusCandidate(10, 1_000L, 3_000L),
            AppleTranslationFocusCandidate(11, 1_900L, 2_700L),
        )
        // If the actual source holder clock proves row 10, a different
        // translated candidate's later begin cannot steal its highlight.
        assertEquals(0, AppleTranslationFocusPolicy.select(
            meanings, setOf(10, 11), 2_000L, verifiedNativeActiveRow = 10,
        ))
        assertEquals(1, AppleTranslationFocusPolicy.select(
            meanings, setOf(10, 11), 2_000L, verifiedNativeActiveRow = 11,
        ))
        assertEquals(1, AppleTranslationFocusPolicy.select(
            meanings, setOf(10, 11), 2_000L, verifiedNativeActiveRow = null,
        ))
        // Distinct painter instances claiming one native row stay ambiguous.
        assertNull(AppleTranslationFocusPolicy.select(
            meanings + AppleTranslationFocusCandidate(10, 1_000L, 3_000L),
            setOf(10, 11), 2_000L, verifiedNativeActiveRow = 10,
        ))
    }

    @Test fun legacyMeaningActiveRowHelperMustAlsoRejectEqualStartTies() {
        val tied = listOf(
            AppleTranslationLineContext("song", "A", 1_000L, 3_000L, "meaning A", 1L),
            AppleTranslationLineContext("song", "B", 1_000L, 2_500L, "meaning B", 1L),
        )
        assertNull(AppleNativeTranslationHighlightPolicy.activeRowIndex(tied, 1_500L))
        assertEquals(0, AppleNativeTranslationHighlightPolicy.activeRowIndex(tied, 2_700L))
    }

    @Test fun twoNativeActiveRowsHaveOnlyOneMeaningMatchedToSourceClock() {
        val rows = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_100L),
            AppleTranslationFocusCandidate(5, 2_000L, 2_800L),
        )
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(4, 5), 1_950L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(4, 5), 2_250L))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(4, 5), null))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, null, 1_950L))
        assertNull(AppleTranslationFocusPolicy.select(rows, null, null))
    }

    @Test fun seekImmediatelyMovesMeaningEvenBeforeHostActiveSetRefreshes() {
        val rows = listOf(
            AppleTranslationFocusCandidate(11, 1_000L, 1_800L),
            AppleTranslationFocusCandidate(12, 2_000L, 2_800L),
        )
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(11), 2_100L))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(12), 1_100L))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, emptySet(), 1_100L))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(11), 1_100L))
    }

    @Test fun nativeFocusBreaksTieOnlyForTrulyOverlappingTimedRows() {
        val rows = listOf(
            AppleTranslationFocusCandidate(11, 1_000L, 2_200L),
            AppleTranslationFocusCandidate(12, 2_000L, 2_800L),
        )
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(11), 2_100L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(12), 2_100L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, emptySet(), 2_100L))
        assertNull(AppleTranslationFocusPolicy.select(rows, emptySet(), null))
    }

    @Test fun identicalTimedMeaningStartsNeedUniqueNativeCorroboration() {
        val tied = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_500L),
            AppleTranslationFocusCandidate(5, 1_000L, 2_500L),
        )
        // Neither the clock nor a multi-active host set identifies which
        // duplicate row owns the currently spoken Korean meaning.
        assertNull(AppleTranslationFocusPolicy.select(tied, null, 1_500L))
        assertNull(AppleTranslationFocusPolicy.select(tied, setOf(4, 5), 1_500L))
        assertNull(AppleTranslationFocusPolicy.select(tied, emptySet(), 1_500L))
        // A single corroborating native row is evidence, not an ordinal guess.
        assertEquals(0, AppleTranslationFocusPolicy.select(tied, setOf(4), 1_500L))
        assertEquals(1, AppleTranslationFocusPolicy.select(tied, setOf(5), 1_500L))
        // A genuinely later interval still wins over the unresolved earlier tie.
        assertEquals(2, AppleTranslationFocusPolicy.select(
            tied + AppleTranslationFocusCandidate(6, 1_200L, 2_500L),
            null, 1_500L,
        ))
    }

    @Test fun revokedHolderPaintGenerationCannotStealAnotherMeaningFocus() {
        val rows = listOf(
            // This holder is still attached with the old text but its native
            // rebind incremented the generation: its foreground cannot draw.
            AppleTranslationFocusCandidate(4, 1_000L, 2_500L, paintOwned = false),
            AppleTranslationFocusCandidate(5, 1_000L, 2_500L, paintOwned = true),
        )
        // The revoked row cannot paint, but its identical clock still makes
        // the current lyric ambiguous unless native focus proves row 5.
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(4), 1_500L))
        assertNull(AppleTranslationFocusPolicy.select(rows, null, 1_500L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), 1_500L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), null))
        assertNull(AppleTranslationFocusPolicy.select(
            rows.map { it.copy(paintOwned = false) }, setOf(4), 1_500L,
        ))
        // With a genuinely later, non-overlapping native clock, a stale
        // active-position set must not veto the verified new owner.
        val distinct = listOf(
            rows[0].copy(endMs = 1_800L),
            rows[1].copy(beginMs = 2_000L),
        )
        assertEquals(1, AppleTranslationFocusPolicy.select(distinct, setOf(4), 2_200L))
        // A later revoked candidate must not cause an older owned meaning
        // to light up as if it were the newest native source line.
        assertNull(AppleTranslationFocusPolicy.select(
            listOf(
                rows[1].copy(beginMs = 1_000L),
                rows[0].copy(beginMs = 1_200L),
            ), null, 1_500L,
        ))
    }

    @Test fun clockExactEndAndPrestartCannotBorrowAHostStaleFocus() {
        val rows = listOf(AppleTranslationFocusCandidate(4, 0L, 500L))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(4), 0L))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(4), 499L))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(4), 500L))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(4), -1L))
    }

    @Test fun unknownHolderPositionCannotBorrowOtherNativeLineHighlight() {
        val rows = listOf(
            AppleTranslationFocusCandidate(null, 1_000L, 2_100L),
            AppleTranslationFocusCandidate(5, 2_000L, 2_800L),
        )
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), 2_200L))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(4), 1_300L))
    }

    @Test fun activeKoreanTranslationHasExactlyOneVisiblePaintOwner() {
        val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
            lineActive = true,
            foregroundReady = true,
        )
        assertFalse(owners.nativeVisible)
        assertTrue(owners.foregroundVisible)
        assertEquals(AppleTranslationTone.ACTIVE, owners.tone)
    }

    @Test fun unavailableForegroundNeverHidesNativeMeaning() {
        val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
            lineActive = true,
            foregroundReady = false,
        )
        assertTrue(owners.nativeVisible)
        assertFalse(owners.foregroundVisible)
    }

    @Test fun inactiveKoreanTranslationKeepsOneMutedOverlayWithoutPainterFlash() {
        val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
            lineActive = false,
            foregroundReady = true,
        )
        assertFalse(owners.nativeVisible)
        assertTrue(owners.foregroundVisible)
        assertEquals(AppleTranslationTone.MUTED, owners.tone)
    }

    @Test fun inactiveMeaningFallsBackToNativeWhenOverlayIsUnavailable() {
        val owners = AppleNativeTranslationHighlightPolicy.paintOwners(
            lineActive = false,
            foregroundReady = false,
        )
        assertTrue(owners.nativeVisible)
        assertFalse(owners.foregroundVisible)
        assertEquals(AppleTranslationTone.MUTED, owners.tone)
    }

    @Test fun unchangedRebindKeepsPulseButNewSourceOrRecycledTextDoesNot() {
        val existing = AppleTranslationLineContext("song", "駄目", 1000L, 1900L, "안 돼", 2L)
        assertTrue(AppleNativeTranslationHighlightPolicy.canReusePulse(
            existing, existing.copy(), sameViews = true, controlledTextsIntact = true,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.canReusePulse(
            existing, existing.copy(beginMs = 2000L),
            sameViews = true, controlledTextsIntact = true,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.canReusePulse(
            existing, existing.copy(revision = 3L),
            sameViews = true, controlledTextsIntact = true,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.canReusePulse(
            existing, existing.copy(), sameViews = false, controlledTextsIntact = true,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.canReusePulse(
            existing, existing.copy(), sameViews = true, controlledTextsIntact = false,
        ))
    }

    @Test fun pendingMeaningCannotSurviveARebindToAnotherLyricInTheSameSong() {
        val first = AppleTranslationLineContext(
            "song", "君の名は", 1_000L, 2_000L, "너의 이름은", 7L,
        )
        val unchangedCells = listOf("君", "の", "名は")
        val adapter = Any()
        val policy = AppleNativeTranslationHighlightPolicy

        // A redundant bind of the same source/adapter row may retain the
        // painter while Apple's native translation getter catches up.
        assertTrue(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, unchangedCells, adapter, adapter,
        ))
        assertTrue(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, emptyList(), adapter, adapter,
        ))
        assertTrue(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, listOf("", ""), adapter, adapter,
        ))

        // A recycled holder can still display the *old* TextView text for a
        // moment. Equal song/revision and intact text are not enough: the
        // native adapter row is a different lyric, even in a repeated chorus.
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 13, unchangedCells, adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, listOf("別", "の", "歌", "다른 노래"),
            adapter, adapter,
        ))
        // The native observer supplies only song_lyrics_word cells. A Latin
        // continuation is still original text, not proof of a Korean meaning.
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, unchangedCells + "LOVE", adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, null, unchangedCells, adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, -1, "song", 7L, -1, unchangedCells, adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "other song", 7L, 12, unchangedCells, adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 8L, 12, unchangedCells, adapter, adapter,
        ))
        assertFalse(policy.mayKeepPendingPainter(
            first, 12, "song", 7L, 12, unchangedCells, adapter, Any(),
        ))
    }

    @Test fun adapterOwnedFallbackCannotCrossAnotherNativePresentation() {
        // A host adapter may override equals/hashCode; row ownership must
        // use object identity, not equals or a shared adapter row integer.
        class EqualAdapter {
            override fun equals(other: Any?): Boolean = other is EqualAdapter
            override fun hashCode(): Int = 1
        }
        val firstAdapter = EqualAdapter()
        val secondAdapter = EqualAdapter()
        val first = AppleTranslationLineContext(
            "song", "同じ歌詞", 1_000L, 2_000L, "같은 가사", 7L,
        )
        val repeated = first.copy(beginMs = 10_000L, endMs = 11_000L)
        val index = AppleTranslationOccurrenceIndex()
        val cells = listOf("同じ", "歌詞")
        index.remember(first, firstAdapter, 12)

        assertEquals(first, index.verifiedFallback("song", 7L, firstAdapter, 12, cells))
        assertNull(index.verifiedFallback("song", 7L, secondAdapter, 12, cells))
        assertNull(index.verifiedFallback("song", 7L, null, 12, cells))
        index.remember(repeated, secondAdapter, 12)
        assertEquals(repeated, index.verifiedFallback("song", 7L, secondAdapter, 12, cells))
        assertEquals(first, index.verifiedFallback("song", 7L, firstAdapter, 12, cells))
        index.remember(first.copy(revision = 8L), firstAdapter, 12)
        assertNull(index.verifiedFallback("song", 8L, secondAdapter, 12, cells))
    }

    @Test fun unboundMeaningProbeCannotReplaceTheLiveTranslationOccurrenceIndex() {
        val index = AppleTranslationOccurrenceIndex()
        val adapter = Any()
        val first = AppleTranslationLineContext(
            "song", "同じ歌詞", 1_000L, 2_000L, "같은 가사", 7L,
        )
        val next = first.copy(songId = "next", revision = 8L)
        index.remember(first, adapter, 12)
        index.remember(next, null, 12)
        index.remember(next, adapter, null)
        index.remember(next, adapter, -1)
        assertEquals("song", index.indexedSongId)
        assertEquals(7L, index.indexedRevision)
        assertEquals(first, index.verifiedFallback(
            "song", 7L, adapter, 12, listOf("同じ", "歌詞"),
        ))

        index.remember(next, adapter, 12)
        assertEquals("next", index.indexedSongId)
        assertEquals(8L, index.indexedRevision)
        assertNull(index.verifiedFallback(
            "song", 7L, adapter, 12, listOf("同じ", "歌詞"),
        ))
    }

    @Test fun directlyBoundMeaningHolderStillAdvancesPresentationWithoutAdapterRow() {
        val index = AppleTranslationOccurrenceIndex()
        val adapter = Any()
        val first = AppleTranslationLineContext(
            "song", "君の名は", 1_000L, 2_000L, "너의 이름은", 7L,
        )
        val next = first.copy(songId = "next", revision = 8L)
        index.remember(first, adapter, 12)
        // A genuine holder's native getter may lack an adapter-row context.
        // Its exact meaning binding still owns the current presentation,
        // although it must never create an unverified adapter-row mapping.
        index.remember(next, null, null, directHolderBound = true)
        assertEquals("next", index.indexedSongId)
        assertEquals(8L, index.indexedRevision)
        assertNull(index.verifiedFallback(
            "song", 7L, adapter, 12, listOf("君", "の", "名は"),
        ))
        assertNull(index.verifiedFallback(
            "next", 8L, adapter, 12, listOf("君", "の", "名は"),
        ))
    }

    @Test fun sourceComparisonUsesDisplayedGlyphsAndIgnoresOpaqueChildren() {
        val policy = AppleNativeTranslationHighlightPolicy
        val adapter = Any()
        val raw = AppleTranslationLineContext(
            "song", "愛", 1_000L, 2_000L, "사랑", 7L,
        )
        // Native raw line text is traditional, while supported Simplified
        // view-getter conversion displays the dictionary-proven glyph 爱.
        assertFalse(policy.sourceCellsMatchExactly("愛", listOf("爱")))
        assertTrue(policy.sourceCellsMatchExactly("愛", listOf("爱"), "爱"))
        assertTrue(policy.mayKeepPendingPainter(
            raw, 2, "song", 7L, 2, listOf("爱"), adapter, adapter, "爱",
        ))
        val index = AppleTranslationOccurrenceIndex()
        index.remember(raw, adapter, 2)
        assertNull(index.verifiedFallback("song", 7L, adapter, 2, listOf("爱")))
        assertEquals(raw, index.verifiedFallback(
            "song", 7L, adapter, 2, listOf("爱"),
            displaySource = { if (it.sourceText == "愛") "爱" else it.sourceText },
        ))
        assertFalse(policy.sourceCellsMatchExactly("愛", listOf(""), "爱"))
        assertFalse(policy.sourceCellsMatchExactly("君", listOf("君", "LOVE")))
        assertFalse(policy.sourceCellsHaveReadableText(listOf("", "", "")))
        assertTrue(policy.sourceCellsHaveReadableText(listOf("", "君", "")))
    }

    @Test fun displayedSourceRespectsNativeWordSplitsNotWholeLinePhraseConversion() {
        val policy = AppleNativeTranslationHighlightPolicy
        val nativeWords = listOf("反", "覆", "愛")
        val displayed = policy.displayedNativeWordSource(
            originalLine = "反覆愛",
            rawWords = nativeWords,
            displayWord = { if (it == "愛") "爱" else it },
        )
        // Dictionary converts the whole phrase 反覆 -> 反复, but separate
        // word getters do not; the expected TextView cells are 反, 覆, 爱.
        assertEquals("反覆爱", displayed)
        assertTrue(policy.sourceCellsMatchExactly(
            "反覆愛", listOf("反", "覆", "爱"), displayed!!,
        ))
        assertFalse(policy.sourceCellsMatchExactly(
            "反覆愛", listOf("反", "覆", "爱"), "反复爱",
        ))
        assertNull(policy.displayedNativeWordSource(
            originalLine = "反覆愛", rawWords = listOf("反", "愛"),
            displayWord = { it },
        ))
    }

    @Test fun fallbackIsNeverPromotedIntoActualHolderGetterAcrossSourceRetry() {
        val holder = Any()
        val adapter = Any()
        val contexts = AppleTranslationLineContextStore()
        val index = AppleTranslationOccurrenceIndex()
        val resolver = AppleTranslationContextResolver(contexts, index)
        val first = AppleTranslationLineContext("song", "最初", 1_000, 2_000, "나", 7)
        val second = AppleTranslationLineContext("song", "次", 3_000, 4_000, "나는", 7)
        index.remember(first, adapter, 12)

        // Bind #1 sees old native cells A: source-cache fallback is allowed,
        // but it MUST NOT mutate holder getter provenance as layout retries.
        assertEquals(first, resolver.resolve(
            holder, "song", 7L, adapter, 12, listOf("最初"),
        ))
        assertNull(contexts.forHolder(holder))
        // Native source changes to B before translation cells are ready.
        assertNull(resolver.resolve(holder, "song", 7L, adapter, 12, listOf("次")))
        index.remember(second, adapter, 12)
        assertEquals(second, resolver.resolve(
            holder, "song", 7L, adapter, 12, listOf("次"),
        ))
        assertNull(contexts.forHolder(holder))
        // The actual native getter is the only actor permitted to remember.
        contexts.remember(holder, second)
        assertEquals(second, resolver.resolve(holder, "song", 7L, adapter, 12, emptyList()))
    }

    @Test fun staleHolderGetterCannotMaskVerifiedCurrentRevisionOccurrence() {
        val holder = Any()
        val adapter = Any()
        val contexts = AppleTranslationLineContextStore()
        val index = AppleTranslationOccurrenceIndex()
        val resolver = AppleTranslationContextResolver(contexts, index)
        val old = AppleTranslationLineContext(
            "song", "古い", 1_000L, 2_000L, "옛 가사", 4L,
        )
        val current = AppleTranslationLineContext(
            "song", "新しい", 3_000L, 4_000L, "새 가사", 5L,
        )
        contexts.remember(holder, old)
        index.remember(current, adapter, 8)

        // A missed holder-start callback may leave the outgoing getter cache
        // in place. It must not preempt a current scoped occurrence whose
        // native row and source have both been independently corroborated.
        assertSame(current, resolver.resolve(
            holder, "song", 5L, adapter, 8, listOf("新しい"),
        ))
        // Without source proof or the correct adapter row, fail closed.
        assertNull(resolver.resolve(
            holder, "song", 5L, adapter, 8, listOf("別の"),
        ))
        assertNull(resolver.resolve(
            holder, "song", 5L, adapter, 9, listOf("新しい"),
        ))
        // A genuine current holder getter remains the authoritative owner.
        contexts.remember(holder, current)
        assertSame(current, resolver.resolve(
            holder, "song", 5L, adapter, 8, emptyList(),
        ))
    }

    @Test fun staleHolderGetterWithinSameRevisionCannotMaskDifferentNativeSourceRow() {
        val holder = Any()
        val adapter = Any()
        val contexts = AppleTranslationLineContextStore()
        val index = AppleTranslationOccurrenceIndex()
        val resolver = AppleTranslationContextResolver(contexts, index)
        val old = AppleTranslationLineContext(
            "song", "前", 1_000L, 2_000L, "앞", 8L,
        )
        val current = AppleTranslationLineContext(
            "song", "後", 2_000L, 3_000L, "뒤", 8L,
        )
        contexts.remember(holder, old)
        index.remember(current, adapter, 14)
        assertSame(current, resolver.resolve(
            holder, "song", 8L, adapter, 14, listOf("後"),
        ))
        // Neither matching song/revision alone nor a different adapter row
        // proves the old getter still owns the newly rebound source cell.
        assertNull(resolver.resolve(
            holder, "song", 8L, adapter, 15, listOf("後"),
        ))
        assertSame(old, resolver.resolve(
            holder, "song", 8L, adapter, 14, listOf("前"),
        ))
        // Before native source words appear, a real direct getter remains the
        // only available evidence and is still safe to *consider* at bind.
        assertSame(old, resolver.resolve(
            holder, "song", 8L, adapter, 14, emptyList(),
        ))
    }

    @Test fun reusedHolderWithIdenticalChorusTextMustNotBorrowOlderRowClock() {
        val holder = Any()
        val adapter = Any()
        val otherAdapter = Any()
        val contexts = AppleTranslationLineContextStore()
        val index = AppleTranslationOccurrenceIndex()
        val resolver = AppleTranslationContextResolver(contexts, index)
        val repeatedText = "君の名は"
        val first = AppleTranslationLineContext(
            "song", repeatedText, 1_000L, 2_000L, "너의 이름은", 9L,
        )
        val chorus = first.copy(beginMs = 20_000L, endMs = 21_000L)
        contexts.remember(holder, first, adapterOwner = adapter, adapterPosition = 12)
        index.remember(first, adapter, 12)
        index.remember(chorus, adapter, 13)

        assertSame(first, resolver.resolve(
            holder, "song", 9L, adapter, 12, listOf(repeatedText),
        ))
        assertSame(chorus, resolver.resolve(
            holder, "song", 9L, adapter, 13, listOf(repeatedText),
        ))
        assertNull(resolver.resolve(
            holder, "song", 9L, otherAdapter, 13, listOf(repeatedText),
        ))
        assertNull(resolver.resolve(
            holder, "song", 9L, adapter, 14, listOf(repeatedText),
        ))
    }

    @Test fun longLyricsKeepRecentBoundMeaningOccurrencesInsteadOfClearingWholeIndex() {
        val adapter = Any()
        val index = AppleTranslationOccurrenceIndex()
        for (position in 0..513) {
            index.remember(AppleTranslationLineContext(
                "long", "line$position", position * 1_000L,
                position * 1_000L + 900L, "meaning$position", 3L,
            ), adapter, position)
        }
        assertNull(index.verifiedFallback("long", 3L, adapter, 0, listOf("line0")))
        assertNull(index.verifiedFallback("long", 3L, adapter, 1, listOf("line1")))
        assertEquals("meaning2", index.verifiedFallback(
            "long", 3L, adapter, 2, listOf("line2"),
        )?.translation)
        assertEquals("meaning512", index.verifiedFallback(
            "long", 3L, adapter, 512, listOf("line512"),
        )?.translation)
        assertEquals("meaning513", index.verifiedFallback(
            "long", 3L, adapter, 513, listOf("line513"),
        )?.translation)
    }

    @Test fun cachedSourceWithoutTheSameVerifiedAdapterRowNeverOwnsAnotherMeaning() {
        val first = AppleTranslationLineContext(
            "song", "君の名は", 1_000L, 2_000L, "너의 이름은", 7L,
        )
        val repeated = first.copy(beginMs = 20_000L, endMs = 21_000L)
        val byPosition = mapOf(12 to first, 13 to repeated)
        val cells = listOf("君", "の", "名は")
        val policy = AppleNativeTranslationHighlightPolicy

        assertEquals(first, policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 7L, 12, cells,
        ))
        assertEquals(repeated, policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 7L, 13, cells,
        ))
        // If 13 has never supplied its timed native getter, a cached 12
        // cannot be reused merely because its Japanese cells are identical.
        assertNull(policy.verifiedFallbackContext(
            mapOf(12 to first), "song", 7L, "song", 7L, 13, cells,
        ))
        assertNull(policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 7L, null, cells,
        ))
        assertNull(policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 7L, -1, cells,
        ))
        assertNull(policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 7L, 12, cells + "LOVE",
        ))
        assertNull(policy.verifiedFallbackContext(
            byPosition, "song", 7L, "song", 8L, 12, cells,
        ))
        assertNull(policy.verifiedFallbackContext(
            byPosition, "song", 7L, "other", 7L, 12, cells,
        ))
    }

    @Test fun changedTranslationContextMustDropPendingPainterEvenOnLayoutRetry() {
        val first = AppleTranslationLineContext(
            "song", "最初", 1_000L, 2_000L, "처음", 7L,
        )
        val second = first.copy(sourceText = "次", beginMs = 2_000L,
            endMs = 3_000L, translation = "다음")
        val adapter = Any()
        val policy = AppleNativeTranslationHighlightPolicy

        assertTrue(policy.mustStopPendingForContext(
            first, second, 12, "song", 7L, 12, listOf("最初"), adapter, adapter,
        ))
        assertFalse(policy.mustStopPendingForContext(
            first, first.copy(), 12, "song", 7L, 12, listOf("最初"), adapter, adapter,
        ))
        assertTrue(policy.mustStopPendingForContext(
            first, first, 12, "song", 7L, 13, listOf("最初"), adapter, adapter,
        ))
        assertTrue(policy.mustStopPendingForContext(
            first, first, 12, "song", 7L, 12, listOf("次"), adapter, adapter,
        ))
    }

    @Test fun holderBindStartDefersPainterDisposalUntilNewContextIsKnown() {
        val lifecycle = AppleTranslationBindingLifecycle()
        val existing = AppleTranslationLineContext("song", "最初", 1000, 2000, "처음", 4)
        lifecycle.painterInstalled(existing)
        val first = lifecycle.beginHolderBind()
        assertTrue(lifecycle.hasPendingPainter())
        assertEquals(AppleTranslationBindResult.REUSE, lifecycle.completeHolderBind(
            first, existing.copy(), sameViews = true, controlledTextsIntact = true,
        ))
        val second = lifecycle.beginHolderBind()
        assertTrue(lifecycle.hasPendingPainter())
        assertEquals(AppleTranslationBindResult.REUSE, lifecycle.completeHolderBind(
            second, existing.copy(), sameViews = true, controlledTextsIntact = true,
        ))
        val third = lifecycle.beginHolderBind()
        assertEquals(AppleTranslationBindResult.REPLACE, lifecycle.completeHolderBind(
            third, existing.copy(sourceText = "次", beginMs = 2000, endMs = 3000),
            sameViews = true, controlledTextsIntact = true,
        ))
        assertFalse(lifecycle.hasPendingPainter())
    }

    @Test fun oneSelectedKoreanMeaningAtBoundaryOverlapGapAndSeek() {
        val first = AppleTranslationLineContext("song", "one", 1000, 2100, "첫째", 1)
        val next = AppleTranslationLineContext("song", "two", 2000, 2800, "둘째", 1)
        val third = AppleTranslationLineContext("song", "three", 3000, 3900, "셋째", 1)
        val rows = listOf(first, next, third)
        assertEquals(0, AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 1999L))
        assertEquals(1, AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 2000L))
        assertEquals(1, AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 2099L))
        assertNull(AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 2900L))
        assertEquals(2, AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 3100L))
        assertEquals(0, AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, 1200L))
        assertNull(AppleNativeTranslationHighlightPolicy.activeRowIndex(rows, null))
    }

    @Test fun painterOnlyOverridesEligibleKoreanMeaningTarget() {
        assertTrue(AppleNativeTranslationHighlightPolicy.eligibleKoreanMeaning(
            translationVisible = true, koreanTargetSelected = true,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.eligibleKoreanMeaning(
            translationVisible = true, koreanTargetSelected = false,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.eligibleKoreanMeaning(
            translationVisible = false, koreanTargetSelected = true,
        ))
    }

    @Test fun delayedMeaningBindMustRecheckLiveToggleAndTargetLanguage() {
        val policy = AppleNativeTranslationHighlightPolicy
        assertTrue(policy.mayRunQueuedMeaningBind(
            originallyEnabled = true, translationVisibleNow = true,
            koreanTargetSelectedNow = true,
        ))
        assertFalse(policy.mayRunQueuedMeaningBind(
            originallyEnabled = true, translationVisibleNow = false,
            koreanTargetSelectedNow = true,
        ))
        assertFalse(policy.mayRunQueuedMeaningBind(
            originallyEnabled = true, translationVisibleNow = true,
            koreanTargetSelectedNow = false,
        ))
        // A request made while hidden may not later self-enable without a
        // fresh, current bind even if the listener toggles it on meanwhile.
        assertFalse(policy.mayRunQueuedMeaningBind(
            originallyEnabled = false, translationVisibleNow = true,
            koreanTargetSelectedNow = true,
        ))
    }

    @Test fun installedMeaningPaintMustObserveCurrentToggleAfterBind() {
        val policy = AppleNativeTranslationHighlightPolicy
        assertTrue(policy.mayPaintInstalledMeaning(
            verifiedOwner = true, meaningEligibleNow = true,
        ))
        // A previously installed Pulse may still be attached when the host
        // refuses a full RecyclerView rebind while computing its layout.
        assertFalse(policy.mayPaintInstalledMeaning(
            verifiedOwner = true, meaningEligibleNow = false,
        ))
        assertFalse(policy.mayPaintInstalledMeaning(
            verifiedOwner = false, meaningEligibleNow = true,
        ))
    }

    @Test fun brokenPreferenceObservationFailsClosedWithoutCrashingForegroundDraw() {
        val policy = AppleNativeTranslationHighlightPolicy
        assertTrue(policy.safeCurrentMeaningEligibility { true })
        assertFalse(policy.safeCurrentMeaningEligibility { false })
        assertFalse(policy.safeCurrentMeaningEligibility {
            error("Malformed preference state")
        })
    }

    @Test fun repeatedViewBindingRetainsTheSameTranslationClockUntilHolderRecycles() {
        val holder = Any()
        val context = AppleTranslationLineContext("song", "駄目", 1000, 1900, "안 돼", 2)
        val store = AppleTranslationLineContextStore()
        store.remember(holder, context)
        assertSame(context, store.forHolder(holder))
        assertSame(context, store.forHolder(holder))
        store.clear(holder)
        assertNull(store.forHolder(holder))
    }

    @Test fun recycledTranslationViewCannotRestoreOrRecolorAnUnrelatedLyric() {
        val controlledText: CharSequence = StringBuilder("안 돼")
        assertTrue(AppleNativeTranslationHighlightPolicy.isStillBound(
            controlledText, controlledText,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.isStillBound(
            controlledText, StringBuilder("다음 가사"),
        ))
    }
    @Test fun noSourceWordOrReadingViewMayBeMistakenForTranslatedMeaning() {
        val meaning = "머릿속에서 그만둬 라고 외치는 도덕성이"
        assertTrue(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "lyrics_translation_line", meaning, meaning))
        assertFalse(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "song_lyrics_word", meaning, meaning))
        assertFalse(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "lyrics_pronunciation_vertical_container", meaning, meaning))
        assertTrue(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            null, meaning, meaning))
    }

    @Test fun aSingleHangulGlyphInTheVerifiedNativeMeaningCellStillReceivesFocus() {
        assertTrue(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "lyrics_translation_line", "나", "나",
        ))
        // Unknown-ID one-glyph fragments remain unsafe to identify by text.
        assertFalse(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            null, "나", "나",
        ))
    }

    @Test fun delayedOrDuplicatedMeaningFragmentsCannotBorrowNewSourceClock() {
        val policy = AppleNativeTranslationHighlightPolicy
        // New Japanese source and timestamp are already correct, but the
        // native Korean TextView can still contain the previous line's 나.
        assertFalse(policy.verifiedCompleteMeaning("나는", listOf("나")))
        assertTrue(policy.verifiedCompleteMeaning("나는", listOf("나는")))
        assertTrue(policy.verifiedCompleteMeaning("나는", listOf("나", "는")))
        assertTrue(policy.verifiedCompleteMeaning("나", listOf("나")))
        assertFalse(policy.verifiedCompleteMeaning("나는", listOf("나는", "나는")))
        assertFalse(policy.verifiedCompleteMeaning("나는", emptyList()))
        assertTrue(policy.verifiedCompleteMeaning(
            "아무리 말해도, 널 사랑해!", listOf("아무리 말해도,", " 널 사랑해!"),
        ))
        assertFalse(policy.verifiedCompleteMeaning(
            "아무리 말해도, 널 사랑해!", listOf("널 사랑해!"),
        ))
    }

    @Test fun meaningCoverageUsesOnlyVisibleCellsAndAllVisibleNativeMeaningIds() {
        val policy = AppleNativeTranslationHighlightPolicy
        fun named(text: String, shown: Boolean = true) =
            AppleMeaningCellCandidate("lyrics_translation_line", text, shown)
        fun chosen(translation: String, rows: List<AppleMeaningCellCandidate>): List<String> =
            policy.selectMeaningCellIndices(translation, rows).map { rows[it].text }

        val completeWithHiddenDuplicate = listOf(named("나는"), named("나는", false))
        assertEquals(listOf("나는"), chosen("나는", completeWithHiddenDuplicate))
        assertTrue(policy.verifiedCompleteMeaning(
            "나는", chosen("나는", completeWithHiddenDuplicate),
        ))

        val missingVisibleSuffix = listOf(named("나"), named("는", false))
        assertEquals(listOf("나"), chosen("나는", missingVisibleSuffix))
        assertFalse(policy.verifiedCompleteMeaning(
            "나는", chosen("나는", missingVisibleSuffix),
        ))

        val staleVisibleNativeCell = listOf(named("너"), named("나는"))
        assertEquals(listOf("너", "나는"), chosen("나는", staleVisibleNativeCell))
        assertFalse(policy.verifiedCompleteMeaning(
            "나는", chosen("나는", staleVisibleNativeCell),
        ))

        val hiddenNamedWithVisibleAnonymous = listOf(
            named("예전", false), AppleMeaningCellCandidate(null, "나는", true),
        )
        assertEquals(listOf("나는"), chosen("나는", hiddenNamedWithVisibleAnonymous))
        val nativeIdWinsOverAnonymous = listOf(
            AppleMeaningCellCandidate(null, "나는", true), named("나는"),
        )
        assertEquals(listOf("나는"), chosen("나는", nativeIdWinsOverAnonymous))
        assertEquals(listOf(1), policy.selectMeaningCellIndices("나는", nativeIdWinsOverAnonymous))
    }

    @Test fun wrappedKoreanTranslationMustHaveOneNativeLineActiveWindow() {
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(999L, 1000L, 2000L))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(1000L, 1000L, 2000L))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(1999L, 1000L, 2000L))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(2000L, 1000L, 2000L))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(null, 1000L, 2000L))
    }

    @Test fun eachPhysicalLineOfLongTranslationMatchesSameLogicalLine() {
        val full = "안 돼, 안 돼, 안 돼. 머릿속에서 그만둬, 바보야 라고 외치는 도덕성이"
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "안 돼, 안 돼, 안 돼"))
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "머릿속에서"))
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "외치는 도덕성이"))
        assertFalse(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "미소노 나카카라"))
        assertFalse(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "の"))
    }

    @Test fun previousSongIndexedTranslationCannotHighlightOnNewPlaybackClock() {
        // The new song can start before its native meaning getter refreshes
        // the occurrence cache. Old cache identity alone must never focus it.
        assertFalse(AppleTranslationLivePresentationPolicy.canFocus(
            pulseSongId = "old-song", pulseRevision = 4L,
            presentedSongId = "new-song", presentedRevision = 4L,
            indexedSongId = "old-song", indexedRevision = 4L,
        ))
    }

    @Test fun previousTranslationRevisionCannotFocusEvenBeforeFreshGetterArrives() {
        assertFalse(AppleTranslationLivePresentationPolicy.canFocus(
            pulseSongId = "song", pulseRevision = 4L,
            presentedSongId = "song", presentedRevision = 5L,
            indexedSongId = "song", indexedRevision = 4L,
        ))
    }

    @Test fun currentIndexedTranslationKeepsItsNativeWordClock() {
        assertTrue(AppleTranslationLivePresentationPolicy.canFocus(
            pulseSongId = "song", pulseRevision = 5L,
            presentedSongId = "song", presentedRevision = 5L,
            indexedSongId = "song", indexedRevision = 5L,
        ))
        assertFalse(AppleTranslationLivePresentationPolicy.canFocus(
            pulseSongId = "song", pulseRevision = 5L,
            presentedSongId = null, presentedRevision = 5L,
            indexedSongId = "song", indexedRevision = 5L,
        ))
    }
}
