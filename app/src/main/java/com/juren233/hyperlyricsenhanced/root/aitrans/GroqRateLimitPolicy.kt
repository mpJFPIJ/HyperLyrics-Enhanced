package com.juren233.hyperlyricsenhanced.root.aitrans

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** Groq rate limits apply across API keys in the same organization. */
internal object GroqRateLimitPolicy {
    private const val DEFAULT_COOLDOWN_MS = 30_000L
    private const val MAX_COOLDOWN_MS = 600_000L

    fun cooldownMillis(retryAfter: String?): Long =
        retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { seconds ->
            ceil(seconds * 1000.0).toLong().coerceIn(1000L, MAX_COOLDOWN_MS)
        } ?: DEFAULT_COOLDOWN_MS

    class Cooldown {
        private val untilMillis = AtomicLong(0L)

        fun limitFor(durationMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
            val until = nowMillis + durationMillis
            untilMillis.updateAndGet { previous -> maxOf(previous, until) }
        }

        fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
            (untilMillis.get() - nowMillis).coerceAtLeast(0L)
    }

    /** Optional web searches are paced so rapid song changes preserve fallback quota. */
    class ResearchThrottle(private val intervalMillis: Long = 25_000L) {
        private val nextAllowedMillis = AtomicLong(0L)

        fun tryAcquire(nowMillis: Long = System.currentTimeMillis()): Boolean {
            while (true) {
                val previous = nextAllowedMillis.get()
                if (nowMillis < previous) return false
                if (nextAllowedMillis.compareAndSet(previous, nowMillis + intervalMillis)) return true
            }
        }
    }
}
