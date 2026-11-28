package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLyricsVisibilityPolicyTest {
    @Test
    fun `default never changes native Apple toggles`() {
        for (translation in listOf(false, true)) {
            for (pronunciation in listOf(false, true)) {
                assertEquals(
                    AppleLyricsVisibilityPolicy.Result(translation, pronunciation),
                    AppleLyricsVisibilityPolicy.resolve(
                        translation, pronunciation,
                        RootConstants.APPLE_MUSIC_LYRICS_MODE_APPLE,
                        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
                        RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
                    ),
                )
            }
        }
    }

    @Test
    fun `three line hides either row when explicitly disabled`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(true, false),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = false,
                applePronunciation = false,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
            ),
        )
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(false, true),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = true,
                applePronunciation = true,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
            ),
        )
    }

    @Test
    fun `three line requests both native supplemental rows regardless of previous Apple preferences`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(true, true),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = false,
                applePronunciation = false,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
            ),
        )
    }

    @Test
    fun `Apple Music hide buttons take precedence over Enhanced show when available`() {
        val result = AppleLyricsVisibilityPolicy.resolve(
            appleTranslation = false,
            applePronunciation = false,
            mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
            translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
            pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
            translationMenuSelection = false,
            pronunciationMenuSelection = false,
        )
        assertEquals(AppleLyricsVisibilityPolicy.Result(false, false), result)
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(true, false),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = false,
                applePronunciation = false,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
                translationMenuSelection = true,
                pronunciationMenuSelection = false,
            ),
        )
    }

    @Test
    fun `Enhanced explicit hide cannot be overridden by Apple show button`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(false, false),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = true,
                applePronunciation = true,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
                translationMenuSelection = true,
                pronunciationMenuSelection = true,
            ),
        )
    }

    @Test
    fun `custom independently shows and hides translation and pronunciation`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(true, false),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = false,
                applePronunciation = true,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
            ),
        )
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(false, true),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = true,
                applePronunciation = false,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
            ),
        )
    }

    @Test
    fun `custom follow Apple leaves respective track unchanged`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(true, false),
            AppleLyricsVisibilityPolicy.resolve(
                appleTranslation = true,
                applePronunciation = false,
                mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM,
                translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
                pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
            ),
        )
    }

    @Test
    fun `unknown modes and override values fail back to host preferences`() {
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(false, true),
            AppleLyricsVisibilityPolicy.resolve(false, true, -1, 1, 2),
        )
        assertEquals(
            AppleLyricsVisibilityPolicy.Result(false, true),
            AppleLyricsVisibilityPolicy.resolve(
                false, true, RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM, -11, 55,
            ),
        )
    }

    @Test
    fun `three line visibility selects both native tracks even with both Apple toggles off`() {
        val effective = AppleLyricsVisibilityPolicy.resolve(
            appleTranslation = false,
            applePronunciation = false,
            mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_THREE_LINE,
            translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
            pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_FOLLOW_APPLE,
        )
        val tracks = appleNativeSupplementTracks(
            pronunciationSelected = effective.pronunciation,
            translationSelected = effective.translation,
        )
        assertEquals(true, AppleNativeSupplementTrack.TRANSLATION in tracks)
        assertEquals(true, AppleNativeSupplementTrack.PRONUNCIATION in tracks)
    }

    @Test
    fun `custom visibility cannot select a native pronunciation track when explicitly hidden`() {
        val effective = AppleLyricsVisibilityPolicy.resolve(
            appleTranslation = true,
            applePronunciation = true,
            mode = RootConstants.APPLE_MUSIC_LYRICS_MODE_CUSTOM,
            translationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_SHOW,
            pronunciationOverride = RootConstants.APPLE_MUSIC_LYRICS_DISPLAY_HIDE,
        )
        val tracks = appleNativeSupplementTracks(
            pronunciationSelected = effective.pronunciation,
            translationSelected = effective.translation,
        )
        assertEquals(true, AppleNativeSupplementTrack.TRANSLATION in tracks)
        assertEquals(false, AppleNativeSupplementTrack.PRONUNCIATION in tracks)
    }
}
