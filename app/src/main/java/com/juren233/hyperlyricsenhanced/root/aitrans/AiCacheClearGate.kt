package com.juren233.hyperlyricsenhanced.root.aitrans

import java.util.concurrent.atomic.AtomicInteger

/** DB + RAM clear transactions may be waiting for a mutex while a cache reader runs. */
internal class AiCacheClearGate {
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var failedDatabaseDeletion = false

    fun beginClear() {
        inFlight.incrementAndGet()
    }

    /** Called after eviction, even on coroutine cancellation or SQLite failure. */
    fun finishClear(success: Boolean) {
        failedDatabaseDeletion = !success
        inFlight.decrementAndGet()
    }

    fun mayServeCache(): Boolean = inFlight.get() == 0

    fun mayReadDatabase(): Boolean = mayServeCache() && !failedDatabaseDeletion
}
