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
    fun `400 and 401 fail immediately and 429 is left for quota aware handling`() {
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
}
