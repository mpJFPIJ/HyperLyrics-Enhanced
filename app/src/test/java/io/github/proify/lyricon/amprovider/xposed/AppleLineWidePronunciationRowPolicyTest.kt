package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLineWidePronunciationRowPolicyTest {
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
