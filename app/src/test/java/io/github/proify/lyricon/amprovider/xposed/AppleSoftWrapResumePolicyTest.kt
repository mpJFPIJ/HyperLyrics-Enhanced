package io.github.proify.lyricon.amprovider.xposed

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
