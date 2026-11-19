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
        lastValidAgeMs: Long? = null,
    ): Long? {
        val fresh = currentMs?.takeIf { it >= 0L }
        val last = lastValidMs?.takeIf { it >= 0L }
        if (fresh == null) {
            if (!timelineAdvancing) return last
            // Live lyrics must not remain permanently painted at the last
            // sampled syllable if the Exo position getter stops responding.
            // Bridge only a brief measurement outage; longer outages require
            // the real player clock to recover rather than fabricate timing.
            val age = lastValidAgeMs ?: return last
            if (age !in 0L..1_000L) return null
            return last?.plus(age)
        }
        if (!timelineAdvancing && fresh == 0L && !explicitSeekToZero &&
            last != null && last > 0L
        ) return last
        return fresh
    }
}
