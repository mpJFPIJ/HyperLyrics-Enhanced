package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.root.aitrans.AppleWordReadingAlignment
import com.juren233.hyperlyricsenhanced.root.aitrans.SourceReadingSpan
import com.juren233.hyperlyricsenhanced.root.aitrans.WordAlignmentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePronunciationPolicyTest {
    @Test
    fun `fully verified reading prefers Apple native word pronunciation cells`() {
        val words = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("君", "키미"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("の", "노"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("名は", "나와"),
        )
        assertEquals(
            ApplePronunciationPresentationMode.ALIGNED_WORDS,
            ApplePronunciationPolicy.presentationMode(
                "키미노나와", false, listOf("君", "の", "名は"), words,
            ),
        )
        assertEquals(listOf("키미", "노", "나와"),
            ApplePronunciationPolicy.verifiedNativeSegments(
                "키미노나와", listOf("君", "の", "名は"), words,
            ))
    }

    @Test
    fun `verified projected split morpheme is timed by original Apple source cells`() {
        val projection = com.juren233.hyperlyricsenhanced.root.aitrans.AppleNativeReadingProjection
            .project(
                "駄目", listOf("駄", "目"),
                listOf(SourceReadingSpan(0, 2, "駄目", "ダメ", "다메")),
                "다메",
            )
        assertEquals(true, projection?.approximate)
        assertEquals(listOf("다", "메"), projection?.words?.map { it.hangul })
        assertEquals(ApplePronunciationPresentationMode.ALIGNED_WORDS,
            ApplePronunciationPolicy.presentationMode(
                "다메", false, listOf("駄", "目"), projection?.words,
            ))
    }

    @Test
    fun `never sends a grouped Japanese morpheme with an empty native word cell to karaoke renderer`() {
        val grouped = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("ダー", "다린"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("リン", ""),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("ベイビー", "베이비"),
        )
        assertNull(ApplePronunciationPolicy.verifiedNativeSegments(
            "다린베이비", listOf("ダー", "リン", "ベイビー"), grouped,
        ))
        assertEquals(
            ApplePronunciationPresentationMode.LINE_WIDE,
            ApplePronunciationPolicy.presentationMode(
                "다린베이비", false, listOf("ダー", "リン", "ベイビー"), grouped,
            ),
        )
    }

    @Test
    fun `native punctuation with an empty phonetic cell also falls back instead of revealing source glyphs`() {
        val aligned = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("ラブ", "라부"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("！", ""),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("と", "토"),
        )
        assertNull(ApplePronunciationPolicy.verifiedNativeSegments(
            "라부토", listOf("ラブ", "！", "と"), aligned,
        ))
        assertEquals(emptyList<String>(), ApplePronunciationPolicy.displaySegments(
            "라부토", listOf("ラブ", "！", "と"), aligned,
        ))
    }
    @Test fun `grouped source reading keeps complete Hangul but never enters native word timing`() {
        val nativeWords = listOf("駄", "目", "脳", "みそ")
        val mapped = AppleWordReadingAlignment.align(
            "駄目脳みそ", nativeWords,
            listOf(
                SourceReadingSpan(0, 2, "駄目", "ダメ", "다메"),
                SourceReadingSpan(2, 3, "脳", "ノウ", "노"),
                SourceReadingSpan(3, 5, "みそ", "ミソ", "미소"),
            ),
            "다메노미소",
        ) as WordAlignmentResult.Grouped
        assertEquals(ApplePronunciationPresentationMode.LINE_WIDE,
            ApplePronunciationPolicy.presentationMode(
                "다메노미소", false, nativeWords, mapped.words,
            ))
        assertNull(ApplePronunciationPolicy.verifiedNativeSegments(
            "다메노미소", nativeWords, mapped.words,
        ))
    }
    @Test
    fun `refreshes official pronunciation when it arrives after the first presentation`() {
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
    }

    @Test
    fun `keeps fallback refresh and gives official data precedence when both exist`() {
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = true,
                hasOnlinePronunciation = true,
                pronunciationSelected = false,
            ),
        )
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = true,
                pronunciationSelected = true,
            ),
        )
    }

    @Test
    fun `does not refresh a non Apple model or an unselected unavailable model`() {
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = false,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = false,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = false,
            ),
        )
    }

    @Test
    fun `keeps Apple pronunciation display getter non null`() {
        assertEquals("", ApplePronunciationPolicy.nonNullDisplayText(null))
        assertEquals(
            "Kimi no na wa",
            ApplePronunciationPolicy.nonNullDisplayText("Kimi no na wa"),
        )
    }

    @Test
    fun `uses main line timing only for a real supplemented pronunciation`() {
        assertEquals(
            ApplePronunciationWordTrack.MAIN_LINE_TIMING,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = true,
            )
        )
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = true,
                hasOnlinePronunciation = true,
            )
        )
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = false,
            )
        )
    }

    @Test
    fun `accepts official word timing only when every main begin has an exact match`() {
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598, 14_114),
                pronunciationWordBegins = listOf(12_937, 13_598, 14_114),
            )
        )
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598),
                pronunciationWordBegins = listOf(12_937, 13_598, 14_114),
            )
        )
    }

    @Test
    fun `rejects official word timing shifted ahead of the main lyrics`() {
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598, 14_114, 14_616),
                pronunciationWordBegins = listOf(12_527, 13_198, 13_714, 14_216),
            )
        )
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = emptyList(),
                pronunciationWordBegins = listOf(12_527),
            )
        )
    }

    @Test
    fun `maps supplemented romanization across all native main words`() {
        assertEquals(
            listOf("Kimi", "no", "na", "wa"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "Kimi no na wa",
                mainWordTexts = listOf("君", "の", "名", "は"),
            ),
        )
    }

    @Test
    fun `unverified long Hangul cannot be assigned guessed native timing`() {
        val parts = ApplePronunciationPolicy.displaySegments(
            pronunciation = "다메 다메 다메 노미소노 나카 카라 야메로 바카 토 와메쿠",
            mainWordTexts = listOf("駄目", "駄目", "駄目", "脳", "みそ", "の", "中", "から"),
        )
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `unspaced Hangul cannot be fabricated into multiple native cells`() {
        assertTrue(ApplePronunciationPolicy.shouldUseLineTextOnly("키미노나와", 4))
        assertFalse(ApplePronunciationPolicy.shouldUseLineTextOnly("키미 노 나 와", 4))
        val parts = ApplePronunciationPolicy.displaySegments(
            pronunciation = "키미노나와",
            mainWordTexts = listOf("君", "の", "名", "は"),
        )
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `unverified syllable groups are never treated as timed source words`() {
        val parts = ApplePronunciationPolicy.displaySegments(
            pronunciation = "이마 코노세데 키미 다케 다이세이카이",
            mainWordTexts = listOf("今", "この世で", "君", "だけ", "大正", "解"),
        )
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `mismatched source cells and Hangul groups produce no timed cells`() {
        val parts = ApplePronunciationPolicy.displaySegments(
            pronunciation = "키미 노 나 와",
            mainWordTexts = listOf("君の", "名は"),
        )
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `uses individually verified readings for matching Apple native karaoke words`() {
        val aligned = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("駄目", "다메"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("駄目", "다메"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("脳", "노"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("みそ", "미소"),
        )
        assertEquals(
            listOf("다메", "다메", "노", "미소"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "다메 다메 노미소",
                mainWordTexts = listOf("駄目", "駄目", "脳", "みそ"),
                alignedWords = aligned,
            ),
        )
    }

    @Test
    fun `refuses native word cells when a Korean reading cannot be aligned exactly`() {
        val sourceWords = listOf("駄目", "駄目", "脳", "みそ", "の", "中", "から")
        val reading = "다메 다메 노미소노 나카카라"
        assertNull(ApplePronunciationPolicy.verifiedNativeSegments(reading, sourceWords, null))
        assertEquals(
            ApplePronunciationPresentationMode.LINE_WIDE,
            ApplePronunciationPolicy.presentationMode(
                pronunciation = reading,
                hasOfficialTimedWords = false,
                nativeWords = sourceWords,
                alignedWords = null,
            ),
        )
    }

    @Test
    fun `uses Apple word timing only if source and Hangul both match`() {
        val mapping = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("駄目", "다메"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("脳", "노"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("みそ", "미소"),
        )
        assertEquals(
            listOf("다메", "노", "미소"),
            ApplePronunciationPolicy.verifiedNativeSegments(
                "다메 노미소", listOf("駄目", "脳", "みそ"), mapping,
            ),
        )
        assertNull(
            ApplePronunciationPolicy.verifiedNativeSegments(
                "다메 노미소", listOf("駄目", "脳みそ"), mapping,
            ),
        )
        assertNull(
            ApplePronunciationPolicy.verifiedNativeSegments(
                "다메 노미소ㅋㅋ", listOf("駄目", "脳", "みそ"), mapping,
            ),
        )
    }

    @Test
    fun `rejects stale word alignments from another line`() {
        val aligned = listOf(
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("君", "키미"),
            com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation("名", "나"),
        )
        val parts = ApplePronunciationPolicy.displaySegments(
            pronunciation = "노미소",
            mainWordTexts = listOf("脳", "みそ"),
            alignedWords = aligned,
        )
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `groups pronunciation by the Han characters inside native words`() {
        assertEquals(
            listOf("siu sa", "di", "fong", "pei"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "siu sa di fong pei",
                mainWordTexts = listOf("潇洒", "的", "放", "屁"),
            ),
        )
    }

    @Test
    fun `maps the misaligned official Cantonese line onto native main words`() {
        assertEquals(
            listOf("sai", "jyu daai", "fung", "sap tau wong fan", "dik", "gaai", "dou"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "sai jyu daai fung sap tau wong fan dik gaai dou",
                mainWordTexts = listOf("细", "雨带", "风", "湿透黄昏", "的", "街", "道"),
            ),
        )
    }

    @Test
    fun `uses proportional safe fallback when token and character counts differ`() {
        assertEquals(
            listOf("jyut6 jyu5", "go1 ci4"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "jyut6 jyu5 go1 ci4",
                mainWordTexts = listOf("粤语", "歌词"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            ApplePronunciationPolicy.displaySegments("Konnichiwa", mainWordTexts = emptyList()),
        )
    }

    @Test
    fun `keeps system matched Latin Apple pronunciation first`() {
        assertEquals(
            "ja-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = "ja-Latn",
                appleLanguages = listOf("ko-Latn"),
                onlineFallbackLanguage = "und-Latn",
            )
        )
    }

    @Test
    fun `falls back to first Apple pronunciation before online source`() {
        assertEquals(
            "ja-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = listOf(" ", "ja-Hrkt", "ja-Latn"),
                onlineFallbackLanguage = "und-Latn",
            )
        )
    }

    @Test
    fun `uses online fallback language only without an Apple track`() {
        assertEquals(
            "und-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = emptyList(),
                onlineFallbackLanguage = "und-Latn",
            )
        )
        assertNull(
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = emptyList(),
                onlineFallbackLanguage = null,
            )
        )
    }

    @Test
    fun `rejects non Latin Apple and fallback languages`() {
        assertNull(
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = "ja-Hrkt",
                appleLanguages = listOf("zh-Hans", "ko-Hang"),
                onlineFallbackLanguage = "zh-Hani",
            )
        )
    }
}
