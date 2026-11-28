package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOverlayPaintOwnershipTest {
    @Test fun `same lyric text cannot borrow a recycled holder generation`() {
        val owner = AppleOverlayPaintOwnership()
        var attachedGeneration = 42L
        var currentGeneration = 42L
        assertFalse(owner.isCurrent()) // No reading owner has been installed.

        // The caller reads the ReadingView's *current* bind callback, not
        // the generation originally captured when the overlay was created.
        owner.bind { attachedGeneration == currentGeneration }
        assertTrue(owner.isCurrent())
        currentGeneration = 43L
        assertFalse(owner.isCurrent())
        attachedGeneration = 43L
        assertTrue(owner.isCurrent())

        // A thrown binding callback is unknown ownership, never permission
        // to paint an old chorus while the native holder is being recycled.
        owner.bind { throw IllegalStateException("detached") }
        assertFalse(owner.isCurrent())
    }
}
