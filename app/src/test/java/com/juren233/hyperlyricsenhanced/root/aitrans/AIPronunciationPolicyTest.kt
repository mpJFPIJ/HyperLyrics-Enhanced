package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPronunciationPolicyTest {

    @Test fun mixedJapaneseKoreanOriginalStillNeedsJapaneseReading() {
        assertTrue(AIPronunciationPolicy.eligibleForeignLine("君の名は 사랑해"))
        assertFalse(AIPronunciationPolicy.eligibleForeignLine("사랑해"))
        assertTrue(AIPronunciationPolicy.missingReadingIndices(
            listOf(TranslationRequestItem(0, "君の名は 사랑해")), emptyList(),
        ) == setOf(0))
    }

    @Test fun invalidMixedLanguageReadingStillCountsAsMissingForRecovery() {
        val input = listOf(TranslationRequestItem(0, "さよなら 사랑해"))
        val cached = listOf(TranslationItem(0, "안녕, 사랑해", "사요나라"))
        assertTrue(AIPronunciationPolicy.missingReadingIndices(input, cached) == setOf(0))
        assertTrue(AIPronunciationPolicy.missingReadingIndices(input,
            listOf(TranslationItem(0, "안녕, 사랑해", "사요나라 사랑해")),
        ).isEmpty())
    }

    @Test fun chineseDominantSongDoesNotRequestJapaneseReadingsForChineseRows() {
        val lines = listOf(
            TranslationRequestItem(0, "感谢你曾来过"),
            TranslationRequestItem(1, "君の名は"),
            TranslationRequestItem(2, "oh"),
        )
        assertFalse(AIPronunciationPolicy.eligibleForeignLine("感谢你曾来过", chineseDominant = true))
        assertTrue(AIPronunciationPolicy.eligibleForeignLine("君の名は", chineseDominant = true))
        assertTrue(AIPronunciationPolicy.eligibleForeignLine("世界", chineseDominant = false))
        assertTrue(AIPronunciationPolicy.missingReadingIndices(
            lines, emptyList(), chineseDominant = true,
        ) == setOf(1))
    }

    @Test fun multilingualPronunciationMetadataDoesNotIdentifyEveryHanRowAsJapanese() {
        val mixed = Song(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to "zh-Latn,ja-Latn",
            ),
            lyrics = List(9) { RichLyricLine(text = "感谢你曾来过") } +
                RichLyricLine(text = "君の名は"),
        )
        assertTrue(AIPronunciationPolicy.isChineseDominant(mixed))
        assertFalse(AIPronunciationPolicy.hasJapaneseProvenance(mixed))
        val hanOnlyJapanese = Song(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to "ja-Latn",
            ),
            lyrics = listOf(RichLyricLine(text = "世界")),
        )
        assertFalse(AIPronunciationPolicy.isChineseDominant(hanOnlyJapanese))
        assertTrue(AIPronunciationPolicy.hasJapaneseProvenance(hanOnlyJapanese))
    }

    @Test fun englishAdlibsWithoutHanAreStillEligibleForHangulPronunciation() {
        val english = song("Oh", "Yeah", "La la la")
        assertTrue(AIPronunciationPolicy.shouldEnrich(english))
        assertFalse(AIPronunciationPolicy.isChineseDominant(english))
        assertTrue(AIPronunciationPolicy.eligibleForeignLine("La la la",
            AIPronunciationPolicy.isChineseDominant(english)))
    }

    @Test fun appleJapaneseLanguageIncludesKanjiOnlyLyricsWithoutTreatingChineseAsJapanese() {
        val tagged = Song(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to "ja-Latn,ja-Kore",
            ),
            lyrics = listOf(RichLyricLine(text = "世界"), RichLyricLine(text = "未来")),
        )
        assertTrue(AIPronunciationPolicy.shouldEnrich(tagged))
        assertFalse(AIPronunciationPolicy.isChineseDominant(tagged))
        assertFalse(AIPronunciationPolicy.shouldEnrich(song("你好", "世界")))
        assertTrue(AIPronunciationPolicy.isChineseDominant(song("你好", "世界")))
    }

    private fun song(vararg texts: String): Song =
        Song(lyrics = texts.map { RichLyricLine(text = it) })

    @Test fun aSingleMissingLineStillNeedsRepairWhenOtherLinesAreComplete() {
        val input = listOf(
            TranslationRequestItem(0, "ありがとう"),
            TranslationRequestItem(1, "I miss you"),
            TranslationRequestItem(2, "사랑해"),
            TranslationRequestItem(3, "..."),
        )
        val cached = listOf(
            TranslationItem(0, trans = "고마워", roma = "아리가토"),
            TranslationItem(1, trans = "보고 싶어"),
        )
        assertTrue(AIPronunciationPolicy.missingReadingIndices(input, cached) == setOf(1))
        assertTrue(AIPronunciationPolicy.missingReadingIndices(
            input, cached + TranslationItem(1, "보고 싶어", "아이 미스 유"),
        ).isEmpty())
    }

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
        assertTrue(AIPronunciationPolicy.shouldEnrich(hangul))
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
        assertTrue(jp.contains("한국인이 실제 노래 가사"))
        assertFalse(jp.contains("输入输出规范"))
        assertTrue(jp.contains("키미노 나와"))
        assertTrue(jp.contains("Do not collapse"))
        assertTrue(english.contains("Korean Hangul"))
        assertFalse(korean.contains("MUST be Hangul"))
    }

}
