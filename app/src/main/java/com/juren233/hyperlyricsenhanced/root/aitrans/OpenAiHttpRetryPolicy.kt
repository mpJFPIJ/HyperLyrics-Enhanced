package com.juren233.hyperlyricsenhanced.root.aitrans

/**
 * Bounded retry policy for transient API failures.
 *
 * 5xx: short exponential backoff. 429: one retry ONLY when the provider
 * supplies an explicit, short Retry-After header and its error isn't classified
 * as quota exhaustion. Never blindly retry daily-quota or permission failures.
 */
internal object OpenAiHttpRetryPolicy {
    const val MAX_ATTEMPTS = 3
    private const val MAX_DELAY_MS = 30_000L

    /**
     * @param attempt zero-based index of the failed request
     * @param reason provider-error category (never an unsanitized server body)
     * @return delay before another attempt, or null for final failure
     */
    fun nextDelayMillis(
        statusCode: Int,
        attempt: Int,
        retryAfter: String?,
        jitterMillis: Long,
        reason: String? = null,
        alternateKeyAvailable: Boolean = false,
    ): Long? {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS - 1) return null
        val retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
        val explicitDelayMs = retryAfterSeconds?.let {
            if (it > MAX_DELAY_MS / 1_000L) return null
            it * 1_000L
        }
        val minDelay = 1_000L shl attempt
        val delayBeforeJitter = when (statusCode) {
            500, 502, 503, 504 -> maxOf(minDelay, explicitDelayMs ?: minDelay)
            429 -> {
                // A 429 can mean a shared project-wide hard quota. Never retry a
                // classified quota failure. With another user-configured key, a
                // single bounded failover may help a transient key-level limit.
                if (reason == "quota" || attempt > 0) return null
                if (explicitDelayMs == null && !alternateKeyAvailable) return null
                maxOf(minDelay, explicitDelayMs ?: minDelay)
            }
            else -> return null
        }
        return (delayBeforeJitter + jitterMillis.coerceIn(0L, 500L))
            .coerceAtMost(MAX_DELAY_MS)
    }
}
