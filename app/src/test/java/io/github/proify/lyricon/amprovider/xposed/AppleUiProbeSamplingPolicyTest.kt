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
}
