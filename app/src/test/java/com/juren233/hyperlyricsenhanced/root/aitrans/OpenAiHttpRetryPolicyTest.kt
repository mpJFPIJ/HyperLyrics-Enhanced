package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiHttpRetryPolicyTest {
    @Test
    fun `503 retries twice with increasing backoff before giving up`() {
        assertEquals(1_000L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 0, null, 0))
        assertEquals(2_000L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 1, null, 0))
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(503, 2, null, 0))
    }

    @Test
    fun `transient 5xx errors retry but permanent server errors do not`() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals(1_000L, OpenAiHttpRetryPolicy.nextDelayMillis(status, 0, null, 0))
        }
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(501, 0, null, 0))
    }

    @Test
    fun `permanent 4xx and unscheduled 429 fail immediately`() {
        for (status in listOf(400, 401, 403, 404, 429)) {
            assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(status, 0, null, 0))
        }
    }

    @Test
    fun `retry after is respected and requests larger than maximum are abandoned`() {
        assertEquals(5_250L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 0, "5", 250))
        assertEquals(2_000L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 1, "1", 0))
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(503, 0, "60", 0))
    }

    @Test
    fun `jitter is bounded and negative attempts are rejected`() {
        assertEquals(1_500L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 0, null, 1_000))
        assertEquals(1_000L, OpenAiHttpRetryPolicy.nextDelayMillis(503, 0, null, -5))
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(503, -1, null, 0))
    }

    @Test
    fun `429 retries once only with an explicit small Retry After and no hard quota evidence`() {
        assertEquals(
            5_250L,
            OpenAiHttpRetryPolicy.nextDelayMillis(
                statusCode = 429,
                attempt = 0,
                retryAfter = "5",
                jitterMillis = 250,
                reason = "unspecified",
            ),
        )
        assertNull(
            OpenAiHttpRetryPolicy.nextDelayMillis(
                statusCode = 429,
                attempt = 1,
                retryAfter = "5",
                jitterMillis = 0,
                reason = "unspecified",
            ),
        )
    }

    @Test
    fun `429 quota exhaustion and excessive retry interval never retry`() {
        for (retryAfter in listOf(null, "", "invalid", "120")) {
            assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(
                statusCode = 429, attempt = 0, retryAfter = retryAfter,
                jitterMillis = 0, reason = "unspecified",
            ))
        }
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(
            statusCode = 429, attempt = 0, retryAfter = "3",
            jitterMillis = 0, reason = "quota",
        ))
    }

    @Test
    fun `429 cannot bypass retry limit and preserves monotonic safe minimum`() {
        assertEquals(1_000L, OpenAiHttpRetryPolicy.nextDelayMillis(
            statusCode = 429, attempt = 0, retryAfter = "0",
            jitterMillis = 0, reason = "unspecified",
        ))
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(
            statusCode = 429, attempt = 2, retryAfter = "0",
            jitterMillis = 0, reason = "unspecified",
        ))
        assertNull(OpenAiHttpRetryPolicy.nextDelayMillis(
            statusCode = 401, attempt = 0, retryAfter = "1",
            jitterMillis = 0, reason = "unspecified",
        ))
    }
}
