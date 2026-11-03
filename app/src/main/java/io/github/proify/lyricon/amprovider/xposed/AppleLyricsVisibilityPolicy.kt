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
        // Null = no native menu interaction observed. A real user action must
        // win over "Show when available" in three-line/custom mode.
        translationMenuSelection: Boolean? = null,
        pronunciationMenuSelection: Boolean? = null,
    ): Result = when (mode) {
        RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE -> Result(
            // Preserve opt-in three-line defaults, but the host menu's latest
            // explicit show/hide action always wins unless Enhanced Hide is set.
            translation = override(translationMenuSelection ?: true, translationOverride,
                respectNativeMenu = translationMenuSelection != null),
            pronunciation = override(pronunciationMenuSelection ?: true, pronunciationOverride,
                respectNativeMenu = pronunciationMenuSelection != null),
        )
        RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM -> Result(
            translation = override(translationMenuSelection ?: appleTranslation, translationOverride,
                respectNativeMenu = translationMenuSelection != null),
            pronunciation = override(pronunciationMenuSelection ?: applePronunciation, pronunciationOverride,
                respectNativeMenu = pronunciationMenuSelection != null),
        )
        else -> Result(appleTranslation, applePronunciation)
    }

    private fun override(
        hostValue: Boolean,
        value: Int,
        respectNativeMenu: Boolean,
    ): Boolean = when (value) {
        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW ->
            if (respectNativeMenu) hostValue else true
        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE -> false
        else -> hostValue
    }
}
