package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLineWidePronunciationRowPolicyTest {
    @Test fun soleTerminalWrappedSourceRowMustExcludeLaterNativeFlexPeers() {
        val source = 60 to 145
        assertTrue(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, emptyList(),
        ))
        assertTrue(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, listOf(153 to 190, 202 to 247),
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, listOf(100 to 170), // a trailing translation shares the last source row
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, listOf(0 to 200), // unknown host wrapper spans the source
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, listOf(0 to 40), // reversed disjoint native child order
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            source, listOf(153 to 153), // unmeasured trailing child
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.terminalWrapHasNoFollowingPeer(
            60 to 60, emptyList(),
        ))
    }

    @Test fun `native Japanese source font discovery includes supplementary Han`() {
        assertTrue(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("𠮟"))
        assertTrue(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("𠮟る"))
        assertTrue(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("漢字"))
        assertTrue(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("かな"))
        assertTrue(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("ー"))
        assertFalse(AppleLineWidePronunciationRowPolicy.containsJapaneseSourceScript("한글 ABC"))
    }

    @Test fun `supplementary Han cannot disappear from the native source prefix contract`() {
        assertEquals("𠮟あ", AppleLineWidePronunciationRowPolicy.sourceKey("𠮟 あ!"))
        assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
            "𠮟あ", listOf("あ", "Korean meaning"),
        ))
        assertEquals(2, AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
            "𠮟あ", listOf("𠮟", "あ", "Korean meaning"),
        ))
        assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
            "あ", listOf("あ", "𠮟"),
        ))
    }

    @Test fun JapaneseVoicingMarksAreSignificantInHolderSourceIdentity() {
        for (mark in listOf('\u3099', '\u309A', '\u309B', '\u309C')) {
            val voiced = "か$mark"
            assertEquals(voiced, AppleLineWidePronunciationRowPolicy.sourceKey(voiced))
            assertFalse(AppleLineWidePronunciationRowPolicy.sourceKey(voiced) ==
                AppleLineWidePronunciationRowPolicy.sourceKey("か"))
            assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                "か", listOf(voiced, "한국어 뜻"),
            ))
            assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                voiced, listOf("か", "한국어 뜻"),
            ))
            assertEquals(1, AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                voiced, listOf(voiced, "한국어 뜻"),
            ))
        }
        // Source punctuation remains safely ignorable for native layout.
        assertEquals("か", AppleLineWidePronunciationRowPolicy.sourceKey("「か！」"))
        assertEquals("ｶﾞ", AppleLineWidePronunciationRowPolicy.sourceKey("ｶﾞ"))
    }

    @Test fun orphanVoicingMarkInNextNativeCellCannotBeMisreadAsTranslation() {
        for (mark in listOf("\u3099", "\u309A", "\u309B", "\u309C", "\uFF9E", "\uFF9F")) {
            assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                "か", listOf("か", mark, "한국어 뜻"),
            ))
            assertEquals(2, AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                "か${mark}", listOf("か", mark, "한국어 뜻"),
            ))
        }
    }

    @Test fun transformedNativeWordsVerifyDisplayCellsAndPreserveRawWrapOffsets() {
        val display = requireNotNull(AppleNativeReadingSourceDisplay.fromWords(
            "反覆の夢", listOf("反", "覆", "の", "夢"),
        ) { word -> if (word == "夢") "梦" else word })
        val payload = AppleLineWidePronunciationPayload(
            "song", "line", "反覆の夢", "한푸쿠노 유메", 1L, nativeDisplay = display,
        )
        val state = AppleLyricsPronunciationState()
        state.rememberLineWide(payload, null)
        val cells = listOf("反覆", "の梦", "a dream")
        assertEquals(payload, state.lineWideForHolder(null, "song", null, 1L, cells))
        assertEquals(listOf("反覆", "の夢"), display.rawCellTexts(cells.take(2)))
        assertNull(display.rawCellTexts(listOf("反复", "の梦")))
        assertNull(AppleNativeReadingSourceDisplay.fromWords("別の夢", listOf("夢")) { "梦" })
        assertTrue(state.hasLineWide("song", "反覆の夢", 1L))
        state.forgetLineWide("song", "反覆の夢")
        assertNull(state.lineWideForHolder(null, "song", null, 1L, cells))
    }

    @Test fun lengthChangingWordConversionPreservesOnlyProvenWordBoundaries() {
        val source = "網際網路の夢"
        val display = requireNotNull(AppleNativeReadingSourceDisplay.fromWords(
            source, listOf("網際網路", "の", "夢"),
        ) { word -> when (word) {
            "網際網路" -> "互联网"
            "夢" -> "梦"
            else -> word
        } })
        val raw = requireNotNull(display.rawCellTexts(listOf("互联网", "の梦")))
        val rows = AppleInterleavedReadingPolicy.plan(
            source, "인터넷 노유메",
            listOf(AppleVisualSourceCell(raw[0], 0, 42), AppleVisualSourceCell(raw[1], 54, 96)),
            listOf(
                ApplePronunciationSourceToken(0, 4, "인터넷", "網際網路"),
                ApplePronunciationSourceToken(4, 5, "노", "の"),
                ApplePronunciationSourceToken(5, 6, "유메", "夢"),
            ),
        )
        assertEquals(listOf("인터넷", "노유메"), rows?.map { it.text })
        assertNull(display.rawCellTexts(listOf("互联", "网の梦")))
    }

    @Test fun differentRawSourcesWithIdenticalDisplayRequireTheNativeHolder() {
        val state = AppleLyricsPronunciationState()
        val firstHolder = Any()
        val original = AppleLineWidePronunciationPayload(
            "s", "1", "夢", "유메", 1, nativeDisplay =
                AppleNativeReadingSourceDisplay.fromWords("夢", listOf("夢")) { "梦" },
        )
        val other = original.copy(lineKey = "2", sourceText = "梦", nativeDisplay = null)
        state.rememberLineWide(original, firstHolder)
        state.rememberLineWide(other, Any())
        assertNull(state.lineWideForHolder(null, "s", null, 1, listOf("梦")))
        assertEquals(original, state.lineWideForHolder(firstHolder, "s", null, 1))
    }

    @Test fun nativePromotionClearsOnlyTheMatchingHolderFallback() {
        val state = AppleLyricsPronunciationState()
        val holder = Any()
        val other = Any()
        val payload = AppleLineWidePronunciationPayload("s", "a", "夢", "유메", 1)
        state.rememberLineWide(payload, holder)
        state.rememberLineWide(payload.copy(lineKey = "b"), other)
        state.forgetLineWide("s", "別", holder)
        assertEquals(payload, state.lineWideForHolder(holder, "s", null, 1))
        state.forgetLineWide("s", "夢", holder)
        assertNull(state.lineWideForHolder(holder, "s", null, 1))
        assertFalse(state.hasLineWide("s", "夢", 1, holder = holder))
        assertTrue(state.hasLineWide("s", "夢", 1, holder = other))
    }

    @Test fun `pending first measurement must not flash a trailing pronunciation block`() {
        assertTrue(AppleLineWidePronunciationRowPolicy.deferUntilMeasured(
            verifiedSourceSpans = true, geometryPending = true,
            hasCompatibleExistingRows = false,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.deferUntilMeasured(
            verifiedSourceSpans = true, geometryPending = false,
            hasCompatibleExistingRows = false,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.deferUntilMeasured(
            verifiedSourceSpans = false, geometryPending = true,
            hasCompatibleExistingRows = false,
        ))
    }

    @Test fun `viewport expansion removes old inserted row breaks before native source measurement`() {
        assertTrue(AppleLineWidePronunciationRowPolicy.needsSourceOnlyRemeasure(
            observedWidths = listOf(520, 520), currentWidth = 900,
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.needsSourceOnlyRemeasure(
            observedWidths = listOf(900, 900), currentWidth = 900,
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.needsSourceOnlyRemeasure(
            observedWidths = listOf(520), currentWidth = 900,
            hasVerifiedProjection = false,
        ))
        val old = AppleNativeSourceMeasureKey(900, 900, listOf(430, 430), listOf(40f))
        assertTrue(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(old),
            current = old.copy(contentWidth = 850),
            hasVerifiedProjection = true,
        ))
        assertTrue(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(old),
            current = old.copy(sourceTextSizes = listOf(44f)),
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(old), current = old, hasVerifiedProjection = true,
        ))
    }

    @Test fun `same-width native flex regrouping invalidates stale interleaved reading rows`() {
        // The source-only layout showed two words sharing the first row.
        // A host margin/alignment update can regroup them without changing
        // any TextView width or font size; old reading insertion points are stale.
        val measured = AppleNativeSourceMeasureKey(
            flexWidth = 900, contentWidth = 900,
            sourceCellWidths = listOf(290, 290, 290),
            sourceTextSizes = listOf(40f, 40f, 40f),
            sourceCellVisualRows = listOf(0, 0, 1),
        )
        assertTrue(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(measured),
            current = measured.copy(sourceCellVisualRows = listOf(0, 1, 1)),
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(measured),
            current = measured.copy(sourceCellVisualRows = listOf(0, 0, 1)),
            hasVerifiedProjection = true,
        ))
        // A not-yet-measured source cannot disprove a previously verified
        // grouping during the native host's own layout transition.
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(measured),
            current = measured.copy(sourceCellVisualRows = null),
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(measured),
            current = measured.copy(sourceCellVisualRows = listOf(0, 1, 1)),
            hasVerifiedProjection = false,
        ))
    }

    @Test fun `same-size native typeface replacement invalidates measured Hangul projection`() {
        // A font/style update can preserve TextView widths and measured row
        // membership; source font identity is still part of reading geometry.
        val before = AppleNativeSourceMeasureKey(
            flexWidth = 720, contentWidth = 720,
            sourceCellWidths = listOf(300, 320),
            sourceTextSizes = listOf(39f, 39f),
            sourceCellVisualRows = listOf(0, 1),
            sourceTypefaceIdentities = listOf(101, 101),
        )
        assertTrue(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(before),
            current = before.copy(sourceTypefaceIdentities = listOf(202, 202)),
            hasVerifiedProjection = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(before), current = before,
            hasVerifiedProjection = true,
        ))
        // Without a proven source↔reading split the host needs no *extra*
        // source-only row measurement; the fallback painter's font owner is
        // checked separately on ordinary view reuse.
        assertFalse(AppleLineWidePronunciationRowPolicy.measurementChanged(
            previous = listOf(before),
            current = before.copy(sourceTypefaceIdentities = listOf(202, 202)),
            hasVerifiedProjection = false,
        ))
    }

    @Test fun `grouped Hangul fallback identifies missing source anchors versus rejected row split`() {
        fun gate(verified: Boolean) = AppleLineWideFallbackGatePolicy.classify(
            geometryPending = false,
            rawCellsValid = true,
            nativeWordsSingleLine = true,
            flexRows = 3,
            sourceLettersMatch = true,
            verifiedProjection = verified,
        )
        // The observed multi-row Japanese-then-grouped-Hangul layout can
        // occur even when all the native TextViews are single-line: the
        // source-to-Hangul phonetic mapping may simply be missing.
        assertEquals(AppleLineWideFallbackGate.MISSING_SOURCE_ANCHORS, gate(false))
        // Verified whole-line sound is not sufficient if a token crossing
        // two measured visual rows has no proven sound boundary.
        assertEquals(AppleLineWideFallbackGate.ROW_PROJECTION_REJECTED, gate(true))
        assertEquals(7, gate(false).code)
        assertEquals(8, gate(true).code)
    }

    @Test fun `grouped Hangul fallback reports measured geometry blockers first`() {
        fun gate(
            pending: Boolean = false, raw: Boolean = true,
            single: Boolean = true, flexRows: Int? = 3, matching: Boolean = true,
        ) = AppleLineWideFallbackGatePolicy.classify(
            geometryPending = pending, rawCellsValid = raw,
            nativeWordsSingleLine = single, flexRows = flexRows,
            sourceLettersMatch = matching, verifiedProjection = true,
        )
        assertEquals(AppleLineWideFallbackGate.MEASUREMENT_PENDING, gate(pending = true))
        assertEquals(AppleLineWideFallbackGate.RAW_CELL_UNKNOWN, gate(raw = false))
        assertEquals(AppleLineWideFallbackGate.NATIVE_INTERNAL_WRAP, gate(single = false))
        assertEquals(AppleLineWideFallbackGate.ROW_GEOMETRY_UNKNOWN, gate(flexRows = null))
        assertEquals(AppleLineWideFallbackGate.ONE_FLEX_ROW, gate(flexRows = 1))
        assertEquals(AppleLineWideFallbackGate.SOURCE_IDENTITY_MISMATCH,
            gate(matching = false))
    }

    @Test fun `shown unsplittable Hangul line also watches native font changes`() {
        // A line-wide fallback has no row-splitting proof, but its Typeface
        // still came from Apple's source word when the reading was created.
        assertTrue(AppleLineWidePronunciationRowPolicy.shouldObserveSourceLayout(
            readingShown = true, canInterleave = false,
        ))
        // A not-yet-measured verified split needs its source observer even if
        // the reading was deferred before first display.
        assertTrue(AppleLineWidePronunciationRowPolicy.shouldObserveSourceLayout(
            readingShown = false, canInterleave = true,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldObserveSourceLayout(
            readingShown = false, canInterleave = false,
        ))
    }

    @Test fun `repeat variants can cover different lengths without losing the final Hangul syllable`() {
        val variants = listOf(
            listOf(AppleTimedReadingUnit(0, 2, 100, 200)),
            listOf(AppleTimedReadingUnit(0, 3, 300, 500)),
        )
        assertEquals(listOf(0, 1, 2),
            AppleReadingGlyphCoveragePolicy.offsets("카나나", variants))
        val first = AppleReadingGlyphCoveragePolicy.owners(variants[0])
        val second = AppleReadingGlyphCoveragePolicy.owners(variants[1])
        assertEquals(null, first[2])
        assertEquals(500L, second[2]?.endMs)
        val conflicting = AppleReadingGlyphCoveragePolicy.owners(listOf(
            AppleTimedReadingUnit(0, 2, 100, 200),
            AppleTimedReadingUnit(1, 3, 300, 500),
        ))
        assertEquals(null, conflicting[1])
    }

    @Test fun `verified word karaoke advances left to right rather than brightening all syllables together`() {
        val word = AppleTimedReadingUnit(0, 4, 100L, 500L)
        val value = "카나카나"
        assertEquals(1f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
            value, word, glyphOffset = 0, positionMs = 300L), 0.001f)
        assertEquals(1f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
            value, word, glyphOffset = 1, positionMs = 300L), 0.001f)
        assertEquals(0f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
            value, word, glyphOffset = 2, positionMs = 300L), 0.001f)
        assertEquals(0f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
            value, word, glyphOffset = 3, positionMs = 300L), 0.001f)
        assertEquals(1f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
            value, word, glyphOffset = 3, positionMs = 500L), 0.001f)
        val coarse = word.copy(isWholeLine = true)
        for (offset in value.indices) {
            assertEquals(0.5f, AppleReadingGlyphCoveragePolicy.progressAtGlyph(
                value, coarse, glyphOffset = offset, positionMs = 300L), 0.001f)
        }
    }

    @Test fun `slightly overflowing reading fits source row without orphaning a final syllable`() {
        assertEquals(31.5f, AppleLineWidePronunciationRowPolicy.fitReadingTextPx(
            targetPx = 36f, currentPx = 36f,
            availablePx = 900f, measuredTextPx = 1000f,
        ) ?: 0f, 0.2f)
        // A very long line must retain readable text rather than shrinking
        // to illegible characters just to satisfy the one-line constraint.
        assertNull(AppleLineWidePronunciationRowPolicy.fitReadingTextPx(
            targetPx = 36f, currentPx = 36f,
            availablePx = 400f, measuredTextPx = 1400f,
        ))
    }

    @Test fun existingNativeWordPainterMustNotHaveAnUnverifiedSecondOverlayOwner() {
        // Apple's own native pronunciation word views already paint every
        // karaoke cell. A second manager registered on the line row doubles
        // paint and bypasses the source word's parent gradient.
        assertFalse(AppleLineWidePronunciationRow::class.java.declaredFields.any { field ->
            field.type.simpleName == "AppleNativeHangulWordOverlayManager"
        })
    }

    @Test fun repeatedChorusRequiresAnExactHolderNotASourceTextGuess() {
        val repeated = AppleLineWidePronunciationPayload(
            songId = "s", lineKey = "repeat", sourceText = "好き", pronunciation = "스키",
            revision = 3L,
            timedWords = listOf(AppleTimedSourceWord("好き", 1_000L, 1_900L)),
            repeatTimings = listOf(listOf(AppleTimedSourceWord("好き", 9_000L, 9_900L))),
        )
        assertFalse(AppleLineWidePronunciationRowPolicy.safeSourceFallback(repeated))
        assertTrue(AppleLineWidePronunciationRowPolicy.safeSourceFallback(
            repeated.copy(repeatTimings = emptyList()),
        ))
        val firstWindow = AppleLineWidePronunciationRowPolicy.lineWindowForVariant(repeated.copy(
            lineBeginMs = 1_000L, lineEndMs = 1_900L,
        ), 0)
        assertEquals(1_000L to 1_900L, firstWindow)
        val repeatedWindow = AppleLineWidePronunciationRowPolicy.lineWindowForVariant(repeated.copy(
            lineBeginMs = 1_000L, lineEndMs = 1_900L,
        ), 1)
        assertEquals(null to null, repeatedWindow)
        val later = AppleTimedPronunciationHighlightPolicy.units(
            sourceText = repeated.sourceText,
            pronunciation = repeated.pronunciation,
            timedWords = repeated.repeatTimings.single(),
            lineBeginMs = repeatedWindow.first,
            lineEndMs = repeatedWindow.second,
        )
        assertEquals(9_000L, later.single().beginMs)
        assertEquals(9_900L, later.single().endMs)
    }

    @Test fun `reading uses exactly one outer paint owner after layout`() {
        val ready = AppleHangulReadingPaintPolicy.owners(
            rootAttached = true, rootWidth = 720, rootHeight = 800,
            readingWidth = 660, readingHeight = 54, overlayAttached = true,
        )
        assertFalse(ready.nativeVisible)
        assertTrue(ready.outerOverlayVisible)
        val pending = AppleHangulReadingPaintPolicy.owners(
            rootAttached = true, rootWidth = 720, rootHeight = 800,
            readingWidth = 660, readingHeight = 54, overlayAttached = false,
        )
        assertTrue(pending.nativeVisible)
        assertFalse(pending.outerOverlayVisible)
    }

    @Test fun `an active native line cannot brighten Hangul glyphs without a timing owner`() {
        assertEquals(0.52f, AppleHangulReadingPaintPolicy.timedGlyphBrightness(
            activeVariant = true, glyphHasTimingOwner = false,
        ), 0.001f)
        assertEquals(0.52f, AppleHangulReadingPaintPolicy.timedGlyphBrightness(
            activeVariant = false, glyphHasTimingOwner = true,
        ), 0.001f)
        assertEquals(1f, AppleHangulReadingPaintPolicy.timedGlyphBrightness(
            activeVariant = true, glyphHasTimingOwner = true,
        ), 0.001f)
    }

    @Test fun `unmeasured or detached reading cannot disappear behind outer overlay`() {
        for ((attached, rootWidth, readingHeight) in listOf(
            Triple(false, 720, 54), Triple(true, 0, 54), Triple(true, 720, 0),
        )) {
            val paint = AppleHangulReadingPaintPolicy.owners(
                rootAttached = attached, rootWidth = rootWidth, rootHeight = 800,
                readingWidth = 660, readingHeight = readingHeight, overlayAttached = true,
            )
            assertTrue(paint.nativeVisible)
            assertFalse(paint.outerOverlayVisible)
        }
    }

    @Test fun unchangedReadingAndNativeWordViewIdentitiesAreReusable() {
        val payload = AppleLineWidePronunciationPayload(
            songId = "s", lineKey = "1", sourceText = "君の名は",
            pronunciation = "키미노 나와", revision = 1L,
        )
        val source = listOf(Any(), Any())
        assertTrue(AppleLineWidePronunciationRowPolicy.canReuseReading(
            boundPayload = payload, requestedPayload = payload.copy(),
            oldWords = source, newWords = source,
            currentIndex = 3, insertionIndex = 3,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.canReuseReading(
            boundPayload = payload, requestedPayload = payload.copy(revision = 2),
            oldWords = source, newWords = source,
            currentIndex = 3, insertionIndex = 3,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.canReuseReading(
            boundPayload = payload, requestedPayload = payload,
            oldWords = source, newWords = listOf(Any(), Any()),
            currentIndex = 3, insertionIndex = 3,
        ))
        assertFalse(AppleLineWidePronunciationRowPolicy.canReuseReading(
            boundPayload = payload, requestedPayload = payload,
            oldWords = source, newWords = source,
            currentIndex = 4, insertionIndex = 3,
        ))
    }

    @Test fun nearFutureReadingWakesBeforeLineBegins() {
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayMs(
            onScreen = true, positionMs = 10_000, nextBeginMs = 12_000,
            active = false,
        ))
        assertEquals(60L, AppleReadingPulsePolicy.nextDelayMs(
            onScreen = true, positionMs = 11_700, nextBeginMs = 12_000,
            active = false,
        ))
        assertEquals(16L, AppleReadingPulsePolicy.nextDelayMs(
            onScreen = true, positionMs = 12_020, nextBeginMs = 12_000,
            active = true,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayMs(
            onScreen = false, positionMs = 11_950, nextBeginMs = 12_000,
            active = false,
        ))
    }

    @Test fun frozenNativePlaybackClockMustNotRepaintTheSameKaraokeGradientAtSixtyFps() {
        val active = listOf(listOf(AppleTimedReadingUnit(0, 2, 1_000L, 2_200L)))
        assertEquals(16L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true, positionMs = 1_600L, variants = active,
            timelineAdvancing = true,
        ))
        assertEquals(120L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true, positionMs = 1_600L, variants = active,
            timelineAdvancing = false,
        ))
        assertEquals(120L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true, positionMs = 960L, variants = active,
            timelineAdvancing = false,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = false, positionMs = 1_600L, variants = active,
            timelineAdvancing = false,
        ))
        // Seek recovery can temporarily wake a paused reading sooner, but
        // must never fabricate an advancing native karaoke timestamp.
        assertEquals(60L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 120L, onScreen = true,
            nowElapsedMs = 1_100L, recoveryUntilElapsedMs = 1_300L,
        ))
    }

    @Test fun terminalReadingMustRejectTheSameRecycledSoftWrapSourceAsEarlierFragments() {
        assertTrue(AppleReadingTerminalPaintPolicy.mayPaint(
            holderCurrent = true, softWrapPresent = true, softWrapSourceCurrent = true,
        ))
        assertFalse(AppleReadingTerminalPaintPolicy.mayPaint(
            holderCurrent = true, softWrapPresent = true, softWrapSourceCurrent = false,
        ))
        assertFalse(AppleReadingTerminalPaintPolicy.mayPaint(
            holderCurrent = false, softWrapPresent = true, softWrapSourceCurrent = true,
        ))
        // A legitimate line-wide reading without any native internal wrap
        // still uses its own holder generation as its paint owner.
        assertTrue(AppleReadingTerminalPaintPolicy.mayPaint(
            holderCurrent = true, softWrapPresent = false, softWrapSourceCurrent = false,
        ))
    }

    @Test fun `accepted seek briefly rechecks actual Exo clock even if its first read is stale`() {
        assertEquals(60L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 400L, onScreen = true,
            nowElapsedMs = 1_100L, recoveryUntilElapsedMs = 1_650L,
        ))
        assertEquals(16L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 16L, onScreen = true,
            nowElapsedMs = 1_100L, recoveryUntilElapsedMs = 1_650L,
        ))
        // The extra clock polls are bounded and never run offscreen.
        assertEquals(400L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 400L, onScreen = false,
            nowElapsedMs = 1_100L, recoveryUntilElapsedMs = 1_650L,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 400L, onScreen = true,
            nowElapsedMs = 1_650L, recoveryUntilElapsedMs = 1_650L,
        ))
        assertEquals(60L, AppleReadingPulsePolicy.seekRecoveryDelayMs(
            ordinaryDelayMs = 400L, onScreen = true,
            nowElapsedMs = 1_650L, recoveryUntilElapsedMs = 2_200L,
        ))
    }

    @Test fun `accepted seek recovery transfers only to matching song revision before expiration`() {
        val accepted = AppleScopedReadingSeekRecovery(
            songId = "song-A", revision = 3L, untilElapsedMs = 1_600L,
        )
        assertEquals(1_600L, AppleReadingSeekRecoveryPolicy.deadlineFor(
            accepted, songId = "song-A", revision = 3L, nowElapsedMs = 1_100L,
        ))
        assertEquals(0L, AppleReadingSeekRecoveryPolicy.deadlineFor(
            accepted, songId = "song-B", revision = 3L, nowElapsedMs = 1_100L,
        ))
        assertEquals(0L, AppleReadingSeekRecoveryPolicy.deadlineFor(
            accepted, songId = "song-A", revision = 4L, nowElapsedMs = 1_100L,
        ))
        assertEquals(0L, AppleReadingSeekRecoveryPolicy.deadlineFor(
            accepted, songId = "song-A", revision = 3L, nowElapsedMs = 1_600L,
        ))
        assertEquals(0L, AppleReadingSeekRecoveryPolicy.deadlineFor(
            null, songId = "song-A", revision = 3L, nowElapsedMs = 1_100L,
        ))
    }

    @Test fun upcomingVerifiedWordAfterAnInternalTimingGapIsNotMissed() {
        // The next line-wide variant began at 100 ms, but the next *timed
        // word* begins at 700 ms. At 550 ms there is no active reading unit.
        // Looking up only variant start times would return null and sleep
        // for 400 ms, waking *after* the Japanese word starts.
        val variants = listOf(listOf(
            AppleTimedReadingUnit(0, 1, 100, 200),
            AppleTimedReadingUnit(2, 3, 700, 900),
        ))
        val next = AppleReadingPulsePolicy.nextUpcomingBeginMs(550, variants)
        assertEquals(700L, next)
        assertEquals(60L, AppleReadingPulsePolicy.nextDelayMs(
            onScreen = true, positionMs = 550, nextBeginMs = next,
            active = false,
        ))
        assertNull(AppleReadingPulsePolicy.nextUpcomingBeginMs(901, variants))
    }

    @Test fun upcomingReadingWakeUsesActualWordsAcrossRepeatedChorusVariants() {
        val variants = listOf(
            listOf(AppleTimedReadingUnit(0, 2, 10_000, 10_200)),
            listOf(
                AppleTimedReadingUnit(0, 1, 1_000, 1_300),
                AppleTimedReadingUnit(2, 3, 1_800, 2_100),
            ),
        )
        assertEquals(1_800L, AppleReadingPulsePolicy.nextUpcomingBeginMs(1_600, variants))
        assertEquals(10_000L, AppleReadingPulsePolicy.nextUpcomingBeginMs(2_200, variants))
        assertEquals(1_000L, AppleReadingPulsePolicy.nextUpcomingBeginMs(0, variants))
    }

    @Test fun `ambiguous repeated clocks stay on fast sample until one occurrence takes ownership`() {
        val variants = listOf(
            listOf(AppleTimedReadingUnit(0, 2, 1_000L, 1_600L)),
            listOf(AppleTimedReadingUnit(0, 2, 1_300L, 1_900L)),
        )
        assertNull(AppleTimedPronunciationHighlightPolicy.speakingVariant(
            1_400L, variants,
        ))
        // No guessed Hangul owner, but the next 16-ms Exo read must notice
        // when the overlap ends instead of waiting up to 400 ms.
        assertEquals(16L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true, positionMs = 1_400L, variants = variants,
        ))
        assertEquals(16L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true, positionMs = 1_700L, variants = variants,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = false, positionMs = 1_400L, variants = variants,
        ))
    }

    @Test fun offscreenReadingDoesNotPollPlayerClockOrScanFastTimers() {
        val variants = listOf(listOf(AppleTimedReadingUnit(0, 1, 1_000, 1_200)))
        var playbackPolls = 0
        val playbackClock: () -> Long? = { playbackPolls++; 1_010L }
        val offscreen = AppleReadingPulsePolicy.playbackSample(false, playbackClock)
        assertNull(offscreen)
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = false,
            positionMs = offscreen,
            variants = variants,
        ))
        assertEquals(0, playbackPolls)
        val onscreen = AppleReadingPulsePolicy.playbackSample(true, playbackClock)
        assertEquals(16L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true,
            positionMs = onscreen,
            variants = variants,
        ))
        // The one sampled clock value is also the one supplied to the paint.
        assertEquals(1, playbackPolls)
    }

    @Test fun immediateReadingLayoutNeverSamplesAnUnattachedOrOffscreenClock() {
        var samples = 0
        val clock: () -> Long? = { samples++; 1_234L }
        // An adapter may construct and measure a reading before adding it to
        // the viewport. Neither setText nor onLayout owns an Exo sample then.
        assertNull(AppleReadingPulsePolicy.immediateLayoutSample(
            attached = false, readingOnScreen = true,
            softWrapSourceOnScreen = true, clock = clock,
        ))
        assertNull(AppleReadingPulsePolicy.immediateLayoutSample(
            attached = true, readingOnScreen = false,
            softWrapSourceOnScreen = false, clock = clock,
        ))
        assertEquals(0, samples)
        assertEquals(1_234L, AppleReadingPulsePolicy.immediateLayoutSample(
            attached = true, readingOnScreen = true,
            softWrapSourceOnScreen = false, clock = clock,
        ))
        // The last full-width reading can be below the viewport while an
        // earlier source soft-line and its overlay remain visible.
        assertEquals(1_234L, AppleReadingPulsePolicy.immediateLayoutSample(
            attached = true, readingOnScreen = false,
            softWrapSourceOnScreen = true, clock = clock,
        ))
        assertEquals(2, samples)
    }

    @Test fun canceledSoftWrapRebindCannotRunAfterHolderDetachAndReattach() {
        val gate = AppleReadingRebindRequestGate()
        val beforeDetach = requireNotNull(gate.request())
        assertNull(gate.request()) // one queued callback per current holder
        assertTrue(gate.isPending(beforeDetach))

        // View.post can survive a transient detach. A new holder lifetime
        // must invalidate the old callback, even when the same view reattaches.
        gate.cancel()
        assertFalse(gate.isPending(beforeDetach))
        val afterReattach = requireNotNull(gate.request())
        assertTrue(gate.isPending(afterReattach))
        assertFalse(gate.isPending(beforeDetach))

        // If the old runnable executes late, its finally block cannot
        // discard the newer generation's pending rebind request.
        gate.finish(beforeDetach)
        assertTrue(gate.isPending(afterReattach))
        gate.finish(afterReattach)
        assertFalse(gate.isPending(afterReattach))
        assertTrue(requireNotNull(gate.request()) > afterReattach)
    }

    @Test fun staleReadingPulseCanRearmOnAttachedHolderReuseWithoutDuplicateTimer() {
        val gate = AppleReadingPulseQueueGate()
        assertFalse(gate.request(attached = false))
        assertTrue(gate.request(attached = true))
        assertFalse(gate.request(attached = true)) // one pending pulse

        // The queued callback executes while the previous bind generation is
        // revoked, so it exits without requesting the next pulse.
        gate.delivered()
        assertFalse(gate.request(attached = false))

        // RecyclerView reuses the *same attached* ReadingView for the new
        // generation; onAttachedToWindow is not called a second time.
        assertTrue(gate.request(attached = true))
        assertFalse(gate.request(attached = true))

        // An ordinary successful pulse schedules exactly one successor.
        gate.delivered()
        assertTrue(gate.request(attached = true))
        assertFalse(gate.request(attached = true))
    }

    @Test fun readingPulseQueueResetsOnAcceptedSeekAndDetach() {
        val gate = AppleReadingPulseQueueGate()
        assertTrue(gate.request(attached = true))
        gate.cancel() // remove pending slow idle pulse on accepted seek
        assertTrue(gate.request(attached = true)) // immediate seek wake
        gate.cancel() // holder detach cancels callbacks
        assertFalse(gate.request(attached = false))
        assertTrue(gate.request(attached = true)) // attach after recycle
    }

    @Test fun visibleReadingWakesForTheNextRealUnitAfterAnInternalGap() {
        val variants = listOf(listOf(
            AppleTimedReadingUnit(0, 1, 100, 200),
            AppleTimedReadingUnit(2, 3, 700, 900),
        ))
        assertEquals(60L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true,
            positionMs = 550L,
            variants = variants,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true,
            positionMs = 901L,
            variants = variants,
        ))
        assertEquals(400L, AppleReadingPulsePolicy.nextDelayForReading(
            onScreen = true,
            positionMs = null,
            variants = variants,
        ))
    }

    @Test fun aSeekOnlyWakesTheCurrentVisibleAttachedSongReading() {
        assertTrue(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = "song-a",
            boundRevision = 3L, currentRevision = 3L,
            attached = true, onScreen = true,
        ))
        assertFalse(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = "song-b",
            boundRevision = 3L, currentRevision = 3L,
            attached = true, onScreen = true,
        ))
        assertFalse(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = null,
            boundRevision = 3L, currentRevision = 3L,
            attached = true, onScreen = true,
        ))
        assertFalse(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = "song-a",
            boundRevision = 2L, currentRevision = 3L,
            attached = true, onScreen = true,
        ))
        assertFalse(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = "song-a",
            boundRevision = 3L, currentRevision = 3L,
            attached = false, onScreen = true,
        ))
        assertFalse(AppleReadingSeekWakePolicy.shouldWake(
            boundSongId = "song-a", seekSongId = "song-a",
            boundRevision = 3L, currentRevision = 3L,
            attached = true, onScreen = false,
        ))
    }

    @Test
    fun `verified native source can have Japanese translation following it`() {
        val cells = listOf("君", "の", "名は", "あなたの名前は")
        assertEquals(3, AppleLineWidePronunciationRowPolicy.insertionIndex("君の名は", cells))
        assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex("君の名は", cells))
    }

    @Test
    fun `native pronunciation font nearly matches original Japanese font`() {
        assertEquals(36f, AppleLineWidePronunciationRowPolicy.readingTextPx(40f), 0.001f)
        assertEquals(21.6f, AppleLineWidePronunciationRowPolicy.readingTextPx(24f), 0.001f)
    }

    @Test
    fun `reading is inserted directly after source cells rather than after later translation`() {
        assertEquals(
            3,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "ダーリンベイビー",
                listOf("ダー", "リン", "ベイビー", "달링, 베이비"),
            ),
        )
        assertEquals(
            4,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "駄目駄目脳みそ",
                listOf("駄目", "駄目", "脳", "みそ", "안 돼"),
            ),
        )
    }

    @Test
    fun `punctuation cells stay with Japanese line before Hangul reading`() {
        assertEquals(
            2,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "「やめろ！」", listOf("やめろ", "！", "그만둬"),
            ),
        )
        assertEquals(
            2,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "ラブ！と", listOf("ラブ", "！と", "사랑"),
            ),
        )
    }

    @Test
    fun `an opaque translation child is a barrier and never skipped below the reading`() {
        // Apple Music's translation child has no nested song_lyrics_word TextView.
        assertEquals(
            3,
            AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(
                "ダーリンベイビー", listOf("ダー", "リン", "ベイビー", "", "달링, 베이비"),
            ),
        )
        assertEquals(
            2,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "「やめろ！」", listOf("やめろ", "！", "", "그만둬"),
            ),
        )
    }

    @Test
    fun `unmatched or ambiguous source cannot receive another line reading`() {
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリンベイビー", listOf("駄目", "달링"),
        ))
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリン", listOf("ダーリン달링"),
        ))
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリン", emptyList(),
        ))
    }
    @Test
    fun `matches original karaoke source without depending on visual word breaks`() {
        assertEquals(
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目 駄目 脳みその中から"),
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目駄目脳 みそ の 中 から"),
        )
        assertFalse(
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目脳") ==
                AppleLineWidePronunciationRowPolicy.sourceKey("駄目駄目脳"),
        )
        assertEquals(
            AppleLineWidePronunciationRowPolicy.sourceKey("「やめろ！」"),
            AppleLineWidePronunciationRowPolicy.sourceKey("やめろ"),
        )
    }

    @Test
    fun `never shows a stale or hidden reading on a recycled lyric holder`() {
        val payload = AppleLineWidePronunciationPayload(
            songId = "a", lineKey = "123", sourceText = "みその中から",
            pronunciation = "미소노 나카카라", revision = 5L, sourcePriority = 1,
        )
        assertTrue(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "b", 5L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 6L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, false, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, true, 2))
    }

    @Test
    fun `full width reading must wrap instead of truncating or splitting per native word`() {
        for (width in listOf(320, 360, 430, 968)) {
            assertEquals(width, AppleLineWidePronunciationRowPolicy.fullWidth(width))
        }
        assertEquals(-1, AppleLineWidePronunciationRowPolicy.fullWidth(0))
        assertNull(AppleLineWidePronunciationRowPolicy.readableText("  \n  "))
        assertEquals(
            "노미소노 나카카라 키코에루",
            AppleLineWidePronunciationRowPolicy.readableText("노미소노  나카카라\n 키코에루"),
        )
    }

    @Test
    fun `song switch and holder reuse do not leak old Korean text`() {
        val state = AppleLyricsPronunciationState()
        val holder = Any()
        val original = AppleLineWidePronunciationPayload(
            "song-a", "line-1", "みその中から", "미소노 나카카라", 1L,
        )
        state.rememberLineWide(original, holder)
        assertEquals(
            original,
            state.lineWideForHolder(holder, "song-a", "みその中から", 1L),
        )

        state.beginHolderBinding(holder)
        assertNull(state.lineWideForHolder(holder, "song-b", "みその中から", 1L))
        assertNull(state.lineWideForHolder(holder, "song-a", "別の歌", 1L))

        val newer = original.copy(songId = "song-b", lineKey = "line-2", revision = 2L)
        state.rememberLineWide(newer, holder)
        assertEquals(newer, state.lineWideForHolder(holder, "song-b", "みその中から", 2L))
        assertNull(state.lineWideForHolder(holder, "song-a", "みその中から", 1L))
    }

    @Test
    fun `more than 256 source rows evicts only old unbound Hangul fallbacks`() {
        val state = AppleLyricsPronunciationState()
        fun source(index: Int) = "あ".repeat(index + 1)
        fun remember(index: Int) = state.rememberLineWide(
            AppleLineWidePronunciationPayload(
                "long-song", "line-$index", source(index), "아", 8L,
            ), null,
        )

        (0..257).forEach(::remember)
        assertNull(state.lineWideForHolder(null, "long-song", source(0), 8L))
        assertNull(state.lineWideForHolder(null, "long-song", source(1), 8L))
        assertEquals("line-2", state.lineWideForHolder(
            null, "long-song", source(2), 8L,
        )?.lineKey)
        assertEquals("line-257", state.lineWideForHolder(
            null, "long-song", source(257), 8L,
        )?.lineKey)

        // An observed older source remains eligible after one newer row arrives.
        remember(2)
        remember(258)
        assertNull(state.lineWideForHolder(null, "long-song", source(3), 8L))
        assertEquals("line-2", state.lineWideForHolder(
            null, "long-song", source(2), 8L,
        )?.lineKey)
        assertEquals("line-258", state.lineWideForHolder(
            null, "long-song", source(258), 8L,
        )?.lineKey)
    }

    @Test
    fun `same looking native lyric with conflicting readings is never guessed`() {
        val state = AppleLyricsPronunciationState()
        val first = AppleLineWidePronunciationPayload(
            "song", "a", "生きる", "이키루", 3L,
        )
        state.rememberLineWide(first, null)
        state.rememberLineWide(first.copy(lineKey = "b", pronunciation = "세이키루"), null)
        assertNull(state.lineWideForHolder(null, "song", "生きる", 3L))
    }

    @Test
    fun `English translation after Japanese source must not hide a valid Hangul reading`() {
        val state = AppleLyricsPronunciationState()
        val payload = AppleLineWidePronunciationPayload(
            "song", "line", "ダーリンベイビー", "다아린 베이비", 7L,
        )
        state.rememberLineWide(payload, null)
        val cells = listOf("ダー", "リン", "ベイビー", "Darling baby")
        assertEquals(3, AppleLineWidePronunciationRowPolicy.insertionIndex(payload.sourceText, cells))
        assertEquals(
            3,
            AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(payload.sourceText, cells),
        )
        assertEquals(
            payload,
            state.lineWideForHolder(null, "song", null, 7L, cells),
        )
    }

    @Test
    fun `one cached short reading cannot be inserted inside a longer Japanese lyric`() {
        val state = AppleLyricsPronunciationState()
        val short = AppleLineWidePronunciationPayload("song", "short", "君", "키미", 7L)
        state.rememberLineWide(short, null)
        val differentLine = listOf("君", "の", "心", "Your heart")
        assertEquals(1, AppleLineWidePronunciationRowPolicy.insertionIndex("君", differentLine))
        assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex("君", differentLine))
        assertNull(state.lineWideForHolder(null, "song", null, 7L, differentLine))
    }

    @Test
    fun `complete Japanese source wins over shorter cached prefix`() {
        val state = AppleLyricsPronunciationState()
        state.rememberLineWide(
            AppleLineWidePronunciationPayload("song", "short", "ダー", "다아", 7L), null,
        )
        val complete = AppleLineWidePronunciationPayload(
            "song", "long", "ダーリンベイビー", "다아린 베이비", 7L,
        )
        state.rememberLineWide(complete, null)
        assertEquals(
            complete,
            state.lineWideForHolder(
                null, "song", null, 7L,
                listOf("ダー", "リン", "ベイビー", "Darling baby"),
            ),
        )
    }

    @Test
    fun `official or newly aligned words remove a previous full line candidate`() {
        val state = AppleLyricsPronunciationState()
        val source = "脳みその中から"
        val payload = AppleLineWidePronunciationPayload(
            "song", "a", source, "노미소노 나카카라", 4L,
        )
        state.rememberLineWide(payload, null)
        assertEquals(payload, state.lineWideForHolder(null, "song", source, 4L))
        state.forgetLineWide("song", source)
        assertNull(state.lineWideForHolder(null, "song", source, 4L))
    }

    @Test
    fun `repeated chorus remembers both native timestamp windows`() {
        val state = AppleLyricsPronunciationState()
        val firstHolder = Any()
        val secondHolder = Any()
        val first = AppleLineWidePronunciationPayload(
            "song", "a", "ダーリン", "다아린", 2L,
            timedWords = listOf(AppleTimedSourceWord("ダーリン", 1_000, 2_000)),
        )
        val repeated = first.copy(
            lineKey = "b", timedWords = listOf(AppleTimedSourceWord("ダーリン", 8_000, 9_000)),
        )
        state.rememberLineWide(first, firstHolder)
        state.rememberLineWide(repeated, secondHolder)
        // Different occurrences share source text; that does not verify
        // which native holder owns which source timeline.
        assertFalse(state.hasLineWide("song", "ダーリン", 2L))
        assertTrue(state.hasLineWide("song", "ダーリン", 2L, holder = firstHolder))
        assertTrue(state.hasLineWide("song", "ダーリン", 2L, holder = secondHolder))
        assertEquals(first.timedWords, state.lineWideForHolder(
            firstHolder, "song", null, 2L,
        )?.timedWords)
        assertEquals(repeated.timedWords, state.lineWideForHolder(
            secondHolder, "song", null, 2L,
        )?.timedWords)
        val recovered = state.lineWideForHolder(null, "song", "ダーリン", 2L)
        assertNull(recovered)
        state.forgetLineWide("song", "ダーリン")
        assertFalse(state.hasLineWide("song", "ダーリン", 2L))
    }
}
