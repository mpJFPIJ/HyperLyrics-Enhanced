package io.github.proify.lyricon.amprovider.xposed

/** Sparse, deterministic checkpoints across a lyric session. Sampling only
 * the first six holders permanently hid later malformed chorus and wrap rows.
 * A bounded Fibonacci schedule observes late rows without a per-frame probe. */
internal object AppleUiProbeSamplingPolicy {
    private val checkpoints = setOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233)

    fun shouldRecord(ordinal: Int): Boolean = ordinal in checkpoints
}
