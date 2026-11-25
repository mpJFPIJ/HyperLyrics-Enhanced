package io.github.proify.lyricon.amprovider.xposed

/** Sparse, deterministic checkpoints across the Apple Music process lifetime.
 * The early Fibonacci samples capture first-bind geometry. A very slow fixed
 * cadence keeps subsequent songs observable without per-frame or per-row logs. */
internal object AppleUiProbeSamplingPolicy {
    private val checkpoints = setOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233)

    fun shouldRecord(ordinal: Int): Boolean = ordinal in checkpoints ||
        (ordinal > 233 && ordinal % 233 == 0)
}
