package com.juren233.hyperlyricsenhanced.root.aitrans

/**
 * Small, bounded retry policy for transient server overload.
 *
 * Deliberately avoid retrying 429 here: it can mean an exhausted daily quota.
 * A separate quota-aware policy can handle 429 once the provider reason is known.
 */
internal object OpenAiHttpRetryPolicy {
    const val MAX_ATTEMPTS = 3
    private const val MAX_DELAY_MS = 30_000L

    /**
     * @param attempt zero-based index of the just-failed request
     * @return delay before another attempt, or null for a final failure
     */
    fun nextDelayMillis(
        statusCode: Int,
        attempt: Int,
        retryAfter: String?,
        jitterMillis: Long,
    ): Long? {
        if (statusCode !in setOf(500, 502, 503, 504)) return null
        if (attempt < 0 || attempt >= MAX_ATTEMPTS - 1) return null
        val exponentialMs = 1_000L shl attempt
        val retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()
        // If a server explicitly asks us to wait longer than the bounded
        // window, respect it by not automatically retrying this request.
        val suggestedMs = if (retryAfterSeconds != null && retryAfterSeconds >= 0L) {
            if (retryAfterSeconds > MAX_DELAY_MS / 1_000L) return null
            maxOf(exponentialMs, retryAfterSeconds * 1_000L)
        } else exponentialMs
        return (suggestedMs + jitterMillis.coerceIn(0L, 500L))
            .coerceAtMost(MAX_DELAY_MS)
    }
}
