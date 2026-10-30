package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * Opt-in lyric-row visibility. This is a rendering preference, not a mutation
 * of the host's persisted translation/pronunciation settings or timed lyrics.
 */
internal object AppleLyricsVisibilityPolicy {
    data class Result(val translation: Boolean, val pronunciation: Boolean)

    fun resolve(
        appleTranslation: Boolean,
        applePronunciation: Boolean,
        mode: Int,
        translationOverride: Int,
        pronunciationOverride: Int,
    ): Result = when (mode) {
        RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE -> Result(
            // Three-line remains on by default, but an explicit Hide must win.
            translation = override(true, translationOverride),
            pronunciation = override(true, pronunciationOverride),
        )
        RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM -> Result(
            translation = override(appleTranslation, translationOverride),
            pronunciation = override(applePronunciation, pronunciationOverride),
        )
        else -> Result(appleTranslation, applePronunciation)
    }

    private fun override(hostValue: Boolean, value: Int): Boolean = when (value) {
        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW -> true
        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE -> false
        else -> hostValue
    }
}
