package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AITranslationKeyTest {
    @Test fun clearingCurrentSongRemovesBothOldAndNewOfflineReadingRevisions() {
        val song = Song(name = "IRIS OUT", artist = "Kenshi Yonezu")
        val lines = listOf("駄目", "脳みそ")
        val configs = AiTranslationConfigs(targetLanguage = "한국어")
        val deletionKeys = AITranslationKey.deletionKeys(configs, song, lines)
        assertTrue(AITranslationKey.pronunciation(song, lines) in deletionKeys)
        assertTrue(AITranslationKey.pronunciation(song, lines,
            policyRevision = "sudachi-v1-20260723-full-line-offsets-v2") in deletionKeys)
        assertTrue(AITranslationKey.pronunciation(song, lines,
            policyRevision = "sudachi-v1-20260723-korean-phonetics-v1") in deletionKeys)
        assertTrue(AITranslationKey.calculate(configs, song, lines,
            includePronunciation = true) in deletionKeys)
    }

    @Test fun newSourceAwareTokenizerRetriesDoNotReuseOldOfflinePronunciationCache() {
        val song = Song(name = "IRIS OUT", artist = "Kenshi Yonezu")
        val lines = listOf("ダーリン半端なく", "駄目駄目")
        assertNotEquals(
            AITranslationKey.pronunciation(song, lines),
            AITranslationKey.pronunciation(song, lines,
                policyRevision = "sudachi-v1-20260723-full-line-offsets-v2"),
        )
    }

    @Test fun pronunciationRevisionDoesNotInvalidateAlreadyTranslatedModelCache() {
        val song = Song(name = "IRIS OUT", artist = "Kenshi Yonezu")
        val lines = listOf("駄目 駄目", "脳みそ")
        val configs = AiTranslationConfigs(model = "north-small-translate-1.0", targetLanguage = "한국어")
        val translationKey = AITranslationKey.calculate(configs, song, lines)
        assertNotEquals(
            AITranslationKey.pronunciation(song, lines, policyRevision = "old"),
            AITranslationKey.pronunciation(song, lines, policyRevision = "new"),
        )
        assertEquals(translationKey, AITranslationKey.calculate(configs, song, lines))
    }

    @Test fun changingProviderOrPromptDoesNotReuseStaleModelTranslation() {
        val song = Song(name = "IRIS OUT", artist = "Kenshi Yonezu")
        val lines = listOf("駄目 駄目", "脳みそ")
        val cohere = AiTranslationConfigs(model = "north-small-translate-1.0",
            baseUrl = "https://api.cohere.ai/compatibility/v1", targetLanguage = "한국어")
        val nvidia = cohere.copy(model = "mistral-large", baseUrl = "https://integrate.api.nvidia.com/v1")
        assertNotEquals(AITranslationKey.calculate(cohere, song, lines),
            AITranslationKey.calculate(nvidia, song, lines))
        assertNotEquals(AITranslationKey.calculate(cohere, song, lines),
            AITranslationKey.calculate(cohere.copy(prompt = "literary tone"), song, lines))
    }

    @Test fun phoneticCacheDoesNotDependOnTranslationProvider() {
        val song = Song(name = "IRIS OUT", artist = "Kenshi Yonezu")
        val lines = listOf("駄目 駄目", "脳みそ")
        assertEquals(AITranslationKey.pronunciation(song, lines),
            AITranslationKey.pronunciation(song, lines))
        assertNotEquals(AITranslationKey.pronunciation(song, lines),
            AITranslationKey.calculate(AiTranslationConfigs(), song, lines))
    }
}
