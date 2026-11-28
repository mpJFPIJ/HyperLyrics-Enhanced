package io.github.proify.lyricon.amprovider.xposed.hooks

/** A completed Exo seek is a target, not a measured player-clock sample. */
internal data class AppleAcceptedSeekClockSample(val positionMs: Long, val songId: String)

internal data class ApplePausedClockFallback(val positionMs: Long?, val fromSeek: Boolean)

/**
 * Native Apple source and supplemental Hangul must observe a stable paused
 * clock. Do not reuse the preceding song's position or override an explicit
 * seek to the beginning. This policy changes no audio playback.
 */
internal object ApplePausedPlaybackClockPolicy {
    /** Never let an old/inactive Exo instance overwrite the current lyric clock. */
    fun acceptedSeekClockSample(
        callbackPlayer: Any?,
        positionSourcePlayer: Any?,
        positionMs: Long?,
        songId: String?,
    ): AppleAcceptedSeekClockSample? {
        if (callbackPlayer == null || callbackPlayer !== positionSourcePlayer ||
            positionMs == null || positionMs < 0L || songId.isNullOrBlank()
        ) return null
        return AppleAcceptedSeekClockSample(positionMs, songId)
    }

    /** Strict event sequences distinguish reads and seeks completed within
     * the same millisecond. Callers first scope both to the player and song. */
    fun newestFallback(
        measuredMs: Long?,
        measuredSequence: Long?,
        acceptedSeekMs: Long?,
        acceptedSeekSequence: Long?,
    ): ApplePausedClockFallback {
        val measured = measuredMs?.takeIf { it >= 0L }
        val seek = acceptedSeekMs?.takeIf {
            it >= 0L && acceptedSeekSequence != null && acceptedSeekSequence >= 0L
        }
        val useSeek = seek != null &&
            (measured == null || measuredSequence == null ||
                acceptedSeekSequence!! >= measuredSequence)
        return ApplePausedClockFallback(if (useSeek) seek else measured, useSeek)
    }

    fun select(
        currentMs: Long?,
        lastValidMs: Long?,
        timelineAdvancing: Boolean,
        explicitSeekToZero: Boolean,
        lastValidAgeMs: Long? = null,
        fallbackFromAcceptedSeek: Boolean = false,
    ): Long? {
        val fresh = currentMs?.takeIf { it >= 0L }
        val last = lastValidMs?.takeIf { it >= 0L }
        if (fresh == null) {
            // An accepted seek-to-zero must survive a temporarily missing
            // getter even when the preceding lyric's sample was nonzero.
            if (explicitSeekToZero && !timelineAdvancing) return 0L
            if (!timelineAdvancing) return last
            // Live lyrics must not remain permanently painted at the last
            // sampled syllable if the Exo position getter stops responding.
            // An accepted seek target is NOT a measured/clamped Exo clock.
            // Never advance from that target while the getter is unreadable.
            if (fallbackFromAcceptedSeek) return null
            // Only a real measured sample can bridge a brief getter outage.
            // Without its age there is no bounded interpolation interval.
            val age = lastValidAgeMs ?: return null
            if (age !in 0L..1_000L) return null
            return last?.plus(age)
        }
        if (!timelineAdvancing && fresh == 0L && !explicitSeekToZero &&
            last != null && last > 0L
        ) return last
        return fresh
    }
}
