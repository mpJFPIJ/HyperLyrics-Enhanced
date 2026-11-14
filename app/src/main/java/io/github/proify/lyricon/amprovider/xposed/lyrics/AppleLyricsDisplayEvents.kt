/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** Preferences dispatch only presentation work, never source requests. */
internal enum class AppleLyricsDisplayPreference { TEXT, PRONUNCIATION, BLUR, FONT }

internal fun AppleLyricsSupplementHooks.onAppleLyricsDisplayPreferenceChanged(event: AppleLyricsDisplayPreference) {
    when (event) {
        AppleLyricsDisplayPreference.TEXT -> refreshAppleLyricsDisplay()
        AppleLyricsDisplayPreference.PRONUNCIATION -> {
            clearPendingApplePronunciationRenderPlans()
            refreshAppleLyricsSupplementPresentation()
        }
        AppleLyricsDisplayPreference.BLUR -> refreshAppleLyricsBlurEffect()
        AppleLyricsDisplayPreference.FONT -> refreshAppleSystemFont()
    }
}

/** One transition coordinates pronunciation invalidation and scroll state. */
internal fun AppleLyricsSupplementHooks.onAppleLyricsDisplayTrackChanged(songId: String?) {
    if (currentAppleLyricsSongId == songId) return
    clearPendingApplePronunciationRenderPlans()
    clearPendingAppleLyricsScrollRestore()
    presentationBinding.selectSong(songId)
    scrollPresentationState.clearSnapshot()
}

/** Native presentation has returned; retain the established restore-before-blur ordering. */
internal fun AppleLyricsSupplementHooks.onAppleLyricsPresentationCompleted(fragment: Any, songId: String) {
    restoreAppleLyricsScrollSnapshot(fragment, songId)
    scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
    // Apple's ViewModel may create the karaoke adapter with its own persisted flags.
    // Reapply opt-in visibility on the next frame using a complete holder bind.
    // Original/Apple mode requires no extra work.
    if (appleLyricsDisplayMode() !=
        com.juren233.hyperlyricsenhanced.common.RootConstants.APPLE_MUSIC_LYRICS_MODE_APPLE
    ) {
        refreshAppleLyricsRecyclerView(
            fragment = fragment,
            expectedSongId = songId,
            expectedRevision = null,
            syncDisplayFlags = true,
        )
    }
    onlineSourceMenuHooks().refreshActiveMenu(songId)
}
