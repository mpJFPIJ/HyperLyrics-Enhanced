package com.juren233.hyperlyricsenhanced.service

/** Pure validation rules shared by the NPatch fallback service and unit tests. */
internal object AppleMusicAiTranslationPolicy {
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

    fun isAuthorizedCaller(packagesForUid: Array<String>?): Boolean =
        packagesForUid?.contains(APPLE_MUSIC_PACKAGE) == true

    fun shouldTranslate(
        enabled: Boolean,
        hasLyrics: Boolean,
        hasExistingTranslation: Boolean,
        skipExisting: Boolean,
        forceOverride: Boolean,
    ): Boolean =
        enabled && hasLyrics && (!hasExistingTranslation || !skipExisting || forceOverride)
}
