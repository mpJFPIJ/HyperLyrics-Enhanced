package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSoftWrapResumePolicyTest {
    private val original = AppleSoftWrapResumeGeometry(
        text = "かなかな",
        width = 410,
        textSizePx = 38f,
        lineBounds = listOf(0 to 2, 2 to 4),
        lineSpacingExtra = 0f,
        lineSpacingMultiplier = 1f,
    )

    @Test fun nativePainterRejectsRecycledTextBeforeTheNextHighlightPulse() {
        val sameBreaks = listOf(0 to 2, 2 to 4)
        fun matches(text: CharSequence, lines: List<Pair<Int, Int>>): Boolean =
            AppleSoftWrapResumePolicy.sameSourceAndLines(
                savedText = original.text,
                currentText = text,
                savedLineBounds = original.lineBounds,
                currentLineCount = lines.size,
                getLineStart = { lines[it].first },
                getLineEnd = { lines[it].second },
            )

        assertTrue(matches("かなかな", sameBreaks))
        // Same length and same two physical lines: lineCount alone used to
        // let the previous lyric's Hangul overlay survive the native rebind.
        assertFalse(matches("よるよる", sameBreaks))
        assertFalse(matches("かなかな", listOf(0 to 1, 1 to 4)))
        assertFalse(matches("かなかな", listOf(0 to 4)))
    }

    @Test fun installedPainterFastPathChecksLiveGeometryWithoutAllocatingANewSnapshot() {
        var checks = 0
        fun fast(
            installed: Boolean = true, attached: Boolean = true,
            ownerCurrent: Boolean = true, layoutPending: Boolean = false,
            text: String = "かなかな", lines: List<Pair<Int, Int>> = original.lineBounds,
        ): Boolean = AppleSoftWrapResumePolicy.fastInstalled(
            installed = installed,
            readingAttached = attached,
            sourceAttached = attached,
            holderAttached = attached,
            layoutPending = layoutPending,
            ownerCurrent = ownerCurrent,
        ) {
            checks++
            AppleSoftWrapResumePolicy.sameSourceAndLines(
                savedText = original.text, currentText = text,
                savedLineBounds = original.lineBounds,
                currentLineCount = lines.size,
                getLineStart = { lines[it].first },
                getLineEnd = { lines[it].second },
            )
        }

        assertTrue(fast())
        assertFalse(fast(text = "よるよる"))
        assertFalse(fast(lines = listOf(0 to 3, 3 to 4)))
        assertEquals(3, checks)
        assertFalse(fast(installed = false))
        assertFalse(fast(attached = false))
        assertFalse(fast(ownerCurrent = false))
        assertFalse(fast(layoutPending = true))
        // No geometry or text allocation is even requested for a detached,
        // settling or revoked painter; the normal resume policy handles it.
        assertEquals(3, checks)
    }

    @Test fun settlingSoftWrapAndRevokedHolderDecideBeforeReadingSourceGeometry() {
        fun preflight(
            attached: Boolean = true, pending: Boolean = false,
            currentOwner: Boolean = true,
        ) = AppleSoftWrapResumePolicy.preflight(
            readingAttached = attached,
            sourceAttached = attached,
            holderAttached = attached,
            layoutPending = pending,
            ownerCurrent = currentOwner,
        )
        assertEquals(AppleSoftWrapResumeDecision.WAIT, preflight(pending = true))
        assertEquals(AppleSoftWrapResumeDecision.WAIT, preflight(attached = false))
        assertEquals(AppleSoftWrapResumeDecision.REBIND, preflight(currentOwner = false))
        assertEquals(null, preflight())
    }

    @Test fun detachedAndReattachedHolderCanRestoreTheSameTwoMeasuredJapaneseLines() {
        assertTrue(AppleSoftWrapResumePolicy.canResume(
            saved = original, current = original,
            sourceAttached = true, holderAttached = true, layoutPending = false,
        ))
    }

    @Test fun matchingLineCountCannotHideDifferentUtf16WrapBoundaries() {
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original,
            current = original.copy(lineBounds = listOf(0 to 1, 1 to 4)),
            sourceAttached = true, holderAttached = true, layoutPending = false,
        ))
    }

    @Test fun recycledNativeCellOrNewFontGeometryCannotReuseOldHangulOverlay() {
        val changes = listOf(
            original.copy(text = "よるよる"),
            original.copy(width = 380),
            original.copy(textSizePx = 44f),
            original.copy(lineBounds = listOf(0 to 4)),
        )
        changes.forEach { changed ->
            assertFalse(AppleSoftWrapResumePolicy.canResume(
                saved = original, current = changed,
                sourceAttached = true, holderAttached = true, layoutPending = false,
            ))
        }
    }

    @Test fun reattachWaitsForBothViewsAndTheirNativeLayoutToBeMeasured() {
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original, current = original,
            sourceAttached = false, holderAttached = true, layoutPending = false,
        ))
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original, current = original,
            sourceAttached = true, holderAttached = false, layoutPending = false,
        ))
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original, current = original,
            sourceAttached = true, holderAttached = true, layoutPending = true,
        ))
    }

    @Test fun anotherLineSpacingOwnerCannotBeOverwrittenOnReattachment() {
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original,
            current = original.copy(lineSpacingExtra = 15f),
            sourceAttached = true, holderAttached = true, layoutPending = false,
        ))
        assertFalse(AppleSoftWrapResumePolicy.canResume(
            saved = original,
            current = original.copy(lineSpacingMultiplier = 1.25f),
            sourceAttached = true, holderAttached = true, layoutPending = false,
        ))
    }

    @Test fun settledButIncompatibleGeometryRequiresRebindingRatherThanWaitingForever() {
        assertTrue(AppleSoftWrapResumePolicy.decision(
            saved = original,
            current = original.copy(textSizePx = 38.6f),
            readingAttached = true, sourceAttached = true, holderAttached = true,
            layoutPending = false, ownerCurrent = true,
        ) == AppleSoftWrapResumeDecision.REBIND)
        assertTrue(AppleSoftWrapResumePolicy.decision(
            saved = original, current = original,
            readingAttached = true, sourceAttached = true, holderAttached = true,
            layoutPending = true, ownerCurrent = true,
        ) == AppleSoftWrapResumeDecision.WAIT)
    }

    @Test fun detachedReadingCannotReinstallItsOwnPainterEvenWithAttachedSource() {
        assertTrue(AppleSoftWrapResumePolicy.decision(
            saved = original, current = original,
            readingAttached = false, sourceAttached = true, holderAttached = true,
            layoutPending = false, ownerCurrent = true,
        ) == AppleSoftWrapResumeDecision.WAIT)
    }

    @Test fun sameLyricTextFromAnotherSongOrHolderGenerationMustNotRestore() {
        assertTrue(AppleSoftWrapResumePolicy.decision(
            saved = original, current = original,
            readingAttached = true, sourceAttached = true, holderAttached = true,
            layoutPending = false, ownerCurrent = false,
        ) == AppleSoftWrapResumeDecision.REBIND)
    }

    @Test fun sameWrapOffsetsButChangedTypefacePaddingOrGlyphWidthInvalidatesPainter() {
        val changes = listOf(
            original.copy(typefaceIdentity = "new-font"),
            original.copy(contentWidth = 392),
            original.copy(compoundPaddingLeft = 9),
            original.copy(compoundPaddingRight = 7),
            original.copy(letterSpacing = 0.08f),
            original.copy(textScaleX = 1.1f),
        )
        changes.forEach { changed ->
            assertTrue(AppleSoftWrapResumePolicy.decision(
                saved = original, current = changed,
                readingAttached = true, sourceAttached = true, holderAttached = true,
                layoutPending = false, ownerCurrent = true,
            ) == AppleSoftWrapResumeDecision.REBIND)
        }
    }
}
