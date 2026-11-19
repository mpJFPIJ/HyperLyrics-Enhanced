package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativeTranslationHighlightPolicyTest {
    @Test fun nativeActiveRowWinsWhenClockIsStaleOrMissing() {
        val rows = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_100L),
            AppleTranslationFocusCandidate(5, 2_000L, 2_800L),
            AppleTranslationFocusCandidate(7, 3_000L, 3_900L),
        )
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), 1_600L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(5), null))
        assertNull(AppleTranslationFocusPolicy.select(rows, setOf(9), 2_300L))
        assertNull(AppleTranslationFocusPolicy.select(rows, emptySet(), 2_300L))
    }

    @Test fun twoNativeActiveRowsHaveOnlyOneMeaningMatchedToSourceClock() {
        val rows = listOf(
            AppleTranslationFocusCandidate(4, 1_000L, 2_100L),
            AppleTranslationFocusCandidate(5, 2_000L, 2_800L),
        )
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, setOf(4, 5), 1_950L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(4, 5), 2_250L))
        assertEquals(1, AppleTranslationFocusPolicy.select(rows, setOf(4, 5), null))
        assertEquals(0, AppleTranslationFocusPolicy.select(rows, null, 1_950L))
        assertNull(AppleTranslationFocusPolicy.select(rows, null, null))
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
}
