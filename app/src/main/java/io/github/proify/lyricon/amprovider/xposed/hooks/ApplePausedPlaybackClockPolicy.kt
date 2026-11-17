package io.github.proify.lyricon.amprovider.xposed.hooks

/**
 * Native Apple source and supplemental Hangul must observe a stable paused
 * clock. Do not reuse the preceding song's position or override an explicit
 * seek to the beginning. This policy changes no audio playback.
 */
internal object ApplePausedPlaybackClockPolicy {
    fun select(
        currentMs: Long?,
        lastValidMs: Long?,
        timelineAdvancing: Boolean,
        explicitSeekToZero: Boolean,
    ): Long? {
        val fresh = currentMs?.takeIf { it >= 0L }
        val last = lastValidMs?.takeIf { it >= 0L }
        if (fresh == null) return last
        if (!timelineAdvancing && fresh == 0L && !explicitSeekToZero &&
            last != null && last > 0L
        ) return last
        return fresh
    }
}
