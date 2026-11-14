package com.juren233.hyperlyricsenhanced.root.aitrans

/** Small NIM requests avoid asking the provider to generate an entire song in one SSE stream. */
internal object NvidiaTranslationBatchPolicy {
    private const val LINES_PER_BATCH = 15
    private const val BATCH_LIMIT_NANOS = 75_000_000_000L
    private const val SONG_LIMIT_NANOS = 180_000_000_000L

    fun batches(items: List<TranslationRequestItem>): List<List<TranslationRequestItem>> =
        items.chunked(LINES_PER_BATCH)

    /** Partial or duplicated indices cannot be treated as a successfully translated batch. */
    fun isComplete(requested: List<TranslationRequestItem>, result: List<TranslationItem>): Boolean =
        result.size == requested.size &&
            result.all { it.trans.isNotBlank() } &&
            result.map { it.index }.toSet() == requested.map { it.index }.toSet()

    fun keySlot(batchIndex: Int, retryIndex: Int, keyCount: Int): Int =
        (batchIndex + retryIndex) % keyCount

    /** The song budget is shared by *all* batches, including earlier provider attempts. */
    fun deadlineNanos(songStartedNanos: Long, batchStartedNanos: Long): Long =
        minOf(songStartedNanos + SONG_LIMIT_NANOS, batchStartedNanos + BATCH_LIMIT_NANOS)
}
