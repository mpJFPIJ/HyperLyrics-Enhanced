package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleReadingOpacityPathPolicyTest {
    @Test fun `outer holder foreground bypasses native flex alpha even when words are unchanged`() {
        // Both word paths are genuine source-to-flex paths. The holder overlay
        // does not inherit the common native Flexbox's half-opacity layer.
        val evidence = AppleReadingOpacityPathPolicy.summarize(
            wordToFlexAlphas = listOf(listOf(0.5f, 0.5f), listOf(0.75f)),
            flexToHolderAlphas = listOf(0.5f),
        )!!
        assertEquals(2, evidence.sourceWords)
        assertEquals(250, evidence.nativeWordMinPm)
        assertEquals(750, evidence.nativeWordMaxPm)
        assertEquals(500, evidence.flexToHolderPm)
        assertEquals(125, evidence.overlayWordMinPm)
        assertEquals(375, evidence.overlayWordMaxPm)
        assertEquals(1, evidence.sharedAlphaLayers)
    }

    @Test fun `fully opaque shared path does not create a fictional alpha mismatch`() {
        val evidence = AppleReadingOpacityPathPolicy.summarize(
            wordToFlexAlphas = listOf(listOf(0.8f), listOf(1f)),
            flexToHolderAlphas = listOf(1f, 1f),
        )!!
        assertEquals(1000, evidence.flexToHolderPm)
        assertEquals(800, evidence.overlayWordMinPm)
        assertEquals(1000, evidence.overlayWordMaxPm)
        assertEquals(0, evidence.sharedAlphaLayers)

        // If the Flexbox itself is the overlay root, the shared path has
        // zero intermediate views, not a missing geometry measurement.
        val direct = AppleReadingOpacityPathPolicy.summarize(
            wordToFlexAlphas = listOf(listOf(0.8f)),
            flexToHolderAlphas = emptyList(),
        )!!
        assertEquals(1000, direct.flexToHolderPm)
        assertEquals(800, direct.overlayWordMinPm)
    }

    @Test fun `unknown and invalid view paths do not provide misleading opacity evidence`() {
        assertNull(AppleReadingOpacityPathPolicy.summarize(emptyList(), listOf(1f)))
        assertNull(AppleReadingOpacityPathPolicy.summarize(listOf(emptyList()), listOf(1f)))
        assertNull(AppleReadingOpacityPathPolicy.summarize(
            listOf(listOf(Float.NaN)), listOf(1f),
        ))
        assertNull(AppleReadingOpacityPathPolicy.summarize(
            listOf(listOf(0.5f)), listOf(Float.POSITIVE_INFINITY),
        ))
        assertNull(AppleReadingOpacityPathPolicy.summarize(
            listOf(listOf(-0.1f)), listOf(1f),
        ))
    }
}
