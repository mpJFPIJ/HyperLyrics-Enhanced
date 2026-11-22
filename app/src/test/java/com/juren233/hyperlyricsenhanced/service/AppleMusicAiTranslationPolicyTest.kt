package com.juren233.hyperlyricsenhanced.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicAiTranslationPolicyTest {
    @Test fun providerMeaningAndReadingCannotBeReturnedAsSuccessfulGeneration() {
        val provider = RichLyricLine(text = "Hello", translation = "공급자", roma = "헬로")
        val input = AppleMusicAiTranslationPolicy.sourceOnly(Song(lyrics = listOf(provider))).lyrics!!.single()
        assertNull(input.translation)
        assertNull(input.roma)
        val output = AppleMusicAiTranslationPolicy.transportLine(provider, input, forceOverride = true)
        assertEquals("공급자", output.translation)
        assertTrue(output.metadata!!.getBoolean(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION))
        val generated = input.copy(translation = "안녕")
        val overridden = AppleMusicAiTranslationPolicy.transportLine(provider, generated, forceOverride = true)
        assertEquals("안녕", overridden.translation)
        assertFalse(overridden.metadata?.getBoolean(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION) == true)
        val kept = AppleMusicAiTranslationPolicy.transportLine(provider, generated, forceOverride = false)
        assertEquals("공급자", kept.translation)
        assertTrue(kept.metadata!!.getBoolean(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION))
        val independentlyEqual = AppleMusicAiTranslationPolicy.transportLine(
            provider, input.copy(translation = "공급자"), forceOverride = false,
        )
        assertFalse(independentlyEqual.metadata?.getBoolean(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION) == true)
    }

    @Test fun staleInboundAiMeaningCannotClaimGenerationOrDuplicateAnEqualMeaning() {
        val alternate = LyricMetadataKeys.STANDALONE_GENERATED_TRANSLATION
        val provider = RichLyricLine(text = "ありがとう", translation = "谢谢",
            metadata = lyricMetadataOf(alternate to "이전 응답", "keep" to "other"))
        fun transport(meaning: String?) = AppleMusicAiTranslationPolicy.transportLine(
            provider, provider.copy(translation = meaning), forceOverride = false,
        )
        assertNull(transport(null).metadata?.getString(alternate))
        assertNull(transport("谢谢").metadata?.getString(alternate))
        val independent = transport("고마워")
        assertEquals("고마워", independent.metadata?.getString(alternate))
        assertEquals("other", independent.metadata?.getString("keep"))
    }

    @Test fun phoneticsOnlyOutputStillReachesAppleMusic() {
        assertTrue(AppleMusicAiTranslationPolicy.shouldDeliverResult(0, 3))
        assertTrue(AppleMusicAiTranslationPolicy.shouldDeliverResult(2, 0))
        assertFalse(AppleMusicAiTranslationPolicy.shouldDeliverResult(0, 0))
    }
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
            enabled = false, hasLyrics = true, hasMissingTranslation = true,
            skipExisting = false, forceOverride = false,
        ))
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = false, hasMissingTranslation = true,
            skipExisting = false, forceOverride = false,
        ))
    }

    @Test fun skipsOnlyWhenAllLinesHaveTranslationsUnlessForced() {
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasMissingTranslation = false,
            skipExisting = true, forceOverride = false,
        ))
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasMissingTranslation = false,
            skipExisting = true, forceOverride = true,
        ))
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasMissingTranslation = true,
            skipExisting = true, forceOverride = false,
        ))
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasMissingTranslation = false,
            skipExisting = false, forceOverride = false,
        ))
    }

    @Test fun existingTranslationsNeverSuppressIndependentPronunciationEnrichment() {
        assertTrue(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = true, hasLyrics = true, hasMissingTranslation = false,
            skipExisting = true, forceOverride = false, hasPronunciationWork = true,
        ))
        assertFalse(AppleMusicAiTranslationPolicy.shouldTranslate(
            enabled = false, hasLyrics = true, hasMissingTranslation = false,
            skipExisting = true, forceOverride = false, hasPronunciationWork = true,
        ))
    }
}
