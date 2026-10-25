package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPronunciationPolicyTest {
    private fun song(vararg texts: String): Song =
        Song(lyrics = texts.map { RichLyricLine(text = it) })

    @Test fun enrichesJapaneseAndEnglishLyrics() {
        assertTrue(AIPronunciationPolicy.shouldEnrich(song("君の名は", "ありがとう")))
        assertTrue(AIPronunciationPolicy.shouldEnrich(song("I love you", "forever yours")))
        assertTrue(AIPronunciationPolicy.shouldEnrich(song("君の名は", "I love you")))
    }

    @Test fun avoidsOtherScriptsAndEmptyLyrics() {
        assertFalse(AIPronunciationPolicy.shouldEnrich(song("사랑해", "고마워")))
        assertFalse(AIPronunciationPolicy.shouldEnrich(song("你好", "世界")))
        assertFalse(AIPronunciationPolicy.shouldEnrich(song("...")))
        assertFalse(AIPronunciationPolicy.shouldEnrich(song("안녕", "Hello")))
    }

    @Test fun requestsHangulWhenOnlyLatinRomajiExists() {
        val existingLatin = Song(lyrics = listOf(
            RichLyricLine(text = "こんにちは", roma = "konnichiwa"),
            RichLyricLine(text = "ありがとう", roma = "arigatō"),
        ))
        assertTrue(AIPronunciationPolicy.shouldEnrich(existingLatin))
        val hangul = Song(lyrics = listOf(
            RichLyricLine(text = "こんにちは", roma = "콘니치와"),
            RichLyricLine(text = "ありがとう", roma = "아리가토"),
        ))
        assertFalse(AIPronunciationPolicy.shouldEnrich(hangul))
    }

    @Test fun pronunciationCacheDoesNotDestroyLegacyTranslationKey() {
        val item = song("I love you")
        val cfg = AiTranslationConfigs(targetLanguage = "한국어")
        val old = AITranslationKey.calculate(cfg, item, listOf("I love you"))
        val upgraded = AITranslationKey.calculate(
            cfg, item, listOf("I love you"), includePronunciation = true,
        )
        assertTrue(old != upgraded)
        assertTrue(old == AITranslationKey.calculate(cfg, item, listOf("I love you")))
    }

    @Test fun promptsForPronunciationOnlyForTargetLyrics() {
        val cfg = AiTranslationConfigs(targetLanguage = "한국어")
        val jp = AITranslationPrompt.build(cfg, song("こんにちは"))
        val english = AITranslationPrompt.build(cfg, song("I miss you"))
        val korean = AITranslationPrompt.build(cfg, song("안녕하세요"))
        assertTrue(jp.contains("MUST be Hangul"))
        assertTrue(jp.contains("키미노 나와"))
        assertTrue(english.contains("Korean Hangul"))
        assertFalse(korean.contains("MUST be Hangul"))
    }

}
