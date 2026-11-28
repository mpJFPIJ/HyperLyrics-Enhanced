package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceBindingTrackerTest {
    @Test fun duplicateCallbacksForSameServiceDoNotResyncEverything() {
        val tracker = ServiceBindingTracker<Any>()
        val first = Any()
        val second = Any()

        assertTrue(tracker.bind(first))
        assertFalse(tracker.bind(first))
        assertFalse(tracker.died(second))
        assertFalse(tracker.bind(first))
        assertTrue(tracker.bind(second))
        assertTrue(tracker.died(second))
        assertTrue(tracker.bind(second))
    }
}
