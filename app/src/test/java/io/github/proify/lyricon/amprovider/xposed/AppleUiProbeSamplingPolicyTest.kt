package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleUiProbeSamplingPolicyTest {
    @Test fun lateWrappedLyricsRemainObservableWithoutUnboundedSampling() {
        val selected = (1..300).filter(AppleUiProbeSamplingPolicy::shouldRecord)
        assertEquals(listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233), selected)
        assertTrue(AppleUiProbeSamplingPolicy.shouldRecord(21))
        assertTrue(AppleUiProbeSamplingPolicy.shouldRecord(89))
        assertFalse(AppleUiProbeSamplingPolicy.shouldRecord(300))
    }

    @Test fun laterSongsStillProduceBoundedGeometryCheckpoints() {
        // This process can play many songs without ever restarting its hooks.
        // The first 233 callbacks must not consume the entire diagnostic budget.
        assertTrue(AppleUiProbeSamplingPolicy.shouldRecord(466))
        assertTrue(AppleUiProbeSamplingPolicy.shouldRecord(699))
        assertFalse(AppleUiProbeSamplingPolicy.shouldRecord(467))
        assertFalse(AppleUiProbeSamplingPolicy.shouldRecord(0))
    }
}
