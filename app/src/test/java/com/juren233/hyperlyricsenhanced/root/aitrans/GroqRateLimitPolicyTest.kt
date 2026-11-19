package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqRateLimitPolicyTest {
    @Test fun rateLimitBackoffHonorsRetryAfterAndHasSafeBounds() {
        assertEquals(30_000L, GroqRateLimitPolicy.cooldownMillis(null))
        assertEquals(3_000L, GroqRateLimitPolicy.cooldownMillis("3"))
        assertEquals(1_500L, GroqRateLimitPolicy.cooldownMillis("1.5"))
        assertEquals(600_000L, GroqRateLimitPolicy.cooldownMillis("99999"))
        assertEquals(30_000L, GroqRateLimitPolicy.cooldownMillis("invalid"))
    }

    @Test fun cooldownPreventsRapidRequestsIncludingAcrossKeys() {
        val cooldown = GroqRateLimitPolicy.Cooldown()
        cooldown.limitFor(5_000L, nowMillis = 10_000L)
        assertTrue(cooldown.remainingMillis(nowMillis = 10_001L) > 0)
        assertEquals(0L, cooldown.remainingMillis(nowMillis = 15_001L))
    }

    @Test fun researchIsPacedIndependentlyOfTranslationFallback() {
        val throttle = GroqRateLimitPolicy.ResearchThrottle(intervalMillis = 25_000L)
        assertTrue(throttle.tryAcquire(nowMillis = 10_000L))
        org.junit.Assert.assertFalse(throttle.tryAcquire(nowMillis = 15_000L))
        assertTrue(throttle.tryAcquire(nowMillis = 35_000L))
    }
}
