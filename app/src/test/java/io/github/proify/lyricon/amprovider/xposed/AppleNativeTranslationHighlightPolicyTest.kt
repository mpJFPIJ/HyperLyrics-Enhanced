package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativeTranslationHighlightPolicyTest {
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
