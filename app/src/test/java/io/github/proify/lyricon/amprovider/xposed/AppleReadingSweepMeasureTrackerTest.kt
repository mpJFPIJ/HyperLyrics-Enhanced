package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleReadingSweepMeasureTrackerTest {
    @Test fun `repeated host layout of the same measured reading does not rebuild glyph widths`() {
        val tracker = AppleReadingSweepMeasureTracker()
        val layout = Any()
        val font = Any()
        assertTrue(tracker.markIfChanged(layout, 720, 2, 36f, font, 0f, 1f))
        // Moving a RecyclerView holder does not change its Layout's horizontal
        // glyph advances or the source word sweep's verified time intervals.
        assertFalse(tracker.markIfChanged(layout, 720, 2, 36f, font, 0f, 1f))
        assertFalse(tracker.markIfChanged(layout, 720, 2, 36f, font, 0f, 1f))
    }

    @Test fun `a new native layout or measured wrap invalidates even unchanged view dimensions`() {
        val tracker = AppleReadingSweepMeasureTracker()
        val first = Any()
        val replacement = Any()
        val font = Any()
        assertTrue(tracker.markIfChanged(first, 720, 1, 36f, font, 0f, 1f))
        assertTrue(tracker.markIfChanged(replacement, 720, 1, 36f, font, 0f, 1f))
        assertTrue(tracker.markIfChanged(replacement, 720, 2, 36f, font, 0f, 1f))
        assertTrue(tracker.markIfChanged(replacement, 680, 2, 36f, font, 0f, 1f))
        assertFalse(tracker.markIfChanged(replacement, 680, 2, 36f, font, 0f, 1f))
    }

    @Test fun `font shape changes invalidate and every new timed reading resets the tracker`() {
        val tracker = AppleReadingSweepMeasureTracker()
        val layout = Any()
        val firstFont = Any()
        val nextFont = Any()
        assertTrue(tracker.markIfChanged(layout, 720, 1, 36f, firstFont, 0f, 1f))
        assertTrue(tracker.markIfChanged(layout, 720, 1, 38f, firstFont, 0f, 1f))
        assertTrue(tracker.markIfChanged(layout, 720, 1, 38f, nextFont, 0f, 1f))
        assertTrue(tracker.markIfChanged(layout, 720, 1, 38f, nextFont, 0.04f, 1f))
        assertTrue(tracker.markIfChanged(layout, 720, 1, 38f, nextFont, 0.04f, 1.1f))
        assertFalse(tracker.markIfChanged(layout, 720, 1, 38f, nextFont, 0.04f, 1.1f))
        tracker.invalidate() // setTimedText changes source spans/timed words.
        assertTrue(tracker.markIfChanged(layout, 720, 1, 38f, nextFont, 0.04f, 1.1f))
    }

    @Test fun `first unmeasured layout cannot lock out later measured geometry`() {
        val tracker = AppleReadingSweepMeasureTracker()
        assertTrue(tracker.markIfChanged(null, 0, 0, 36f, null, 0f, 1f))
        assertFalse(tracker.markIfChanged(null, 0, 0, 36f, null, 0f, 1f))
        assertTrue(tracker.markIfChanged(Any(), 720, 1, 36f, Any(), 0f, 1f))
    }
}
