package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLineWidePronunciationRowPolicyTest {
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
        assertEquals(60L, AppleReadingPulsePolicy.nextDelayMs(
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
