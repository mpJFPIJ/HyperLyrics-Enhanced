package com.juren233.hyperlyricsenhanced.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicAiTranslationPolicyTest {
    @Test fun rejectsCallersOtherThanAppleMusic() {
        assertFalse(AppleMusicAiTranslationPolicy.isAuthorizedCaller(null))
        assertFalse(AppleMusicAiTranslationPolicy.isAuthorizedCaller(arrayOf("com.example.other")))
        assertTrue(
            AppleMusicAiTranslationPolicy.isAuthorizedCaller(
                arrayOf("com.apple.android.music")
            )
        )
    }

    @Test fun neverTranslatesWhenDisabledOrLyricsMissing() {
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = false, hasLyrics = true, hasExistingTranslation = false,
            skipExisting = false, forceOverride = false,
        ))
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = false, hasExistingTranslation = false,
            skipExisting = false, forceOverride = false,
        ))
    }

    @Test fun skipsExistingTranslationsUnlessForced() {
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasExistingTranslation = true,
            skipExisting = true, forceOverride = false,
        ))
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasExistingTranslation = true,
            skipExisting = true, forceOverride = true,
        ))
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasExistingTranslation = false,
            skipExisting = true, forceOverride = false,
        ))
    }
}
