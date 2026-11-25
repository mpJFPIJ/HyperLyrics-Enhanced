package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiWebReferenceCacheTest {
    @Test
    fun `grounded mode uses separate cache identity without changing legacy translations`() {
        val config = AiTranslationConfigs(targetLanguage = "ko", webSearchEnabled = false)
        val song = Song(name = "Track", artist = "Singer")
        val lines = listOf("I love you", "ありがとう")
        val legacy = AITranslationKey.calculate(config, song, lines, includePronunciation = true)
        val grounded = AITranslationKey.calculate(
            config.copy(webSearchEnabled = true), song, lines, includePronunciation = true,
        )

        assertNotEquals(legacy, grounded)
        assertEquals(legacy, AITranslationKey.calculate(config, song, lines, includePronunciation = true))
    }
}
