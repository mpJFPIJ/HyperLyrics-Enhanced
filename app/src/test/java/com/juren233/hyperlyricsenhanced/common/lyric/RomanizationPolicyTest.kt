package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizationPolicyTest {
    @Test fun mixedJapaneseKoreanSupplementRetainsEveryOriginalKoreanRun() {
        assertEquals("키미노 나와 사랑해", RomanizationPolicy.sanitizeHangulSupplement(
            "君の名は 사랑해", "키미노 나와 사랑해",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement(
            "君の名は 사랑해", "키미노 나와",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement(
            "君の名は 사랑해", "사랑해",
        ))
        assertEquals("오늘 키미노 나와 사랑해", RomanizationPolicy.sanitizeHangulSupplement(
            "오늘 君の名は 사랑해", "오늘 키미노 나와 사랑해",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement(
            "오늘 君の名は 사랑해", "사랑해 키미노 나와 오늘",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("사랑해", "사랑해"))
    }

    @Test
    fun `keeps Latin romanization for non Latin lyrics`() {
        assertEquals(
            "Kimi no na wa",
            RomanizationPolicy.sanitize("君の名は", "Kimi no na wa"),
        )
        assertEquals(
            "Nǐ hǎo, shìjiè",
            RomanizationPolicy.sanitize("你好，世界", "Nǐ hǎo, shìjiè"),
        )
        assertEquals(
            "annyeong world",
            RomanizationPolicy.sanitize("안녕 world", "annyeong world"),
        )
        assertEquals(
            "nei hou Hello",
            RomanizationPolicy.sanitize("你好 Hello", "nei hou Hello"),
        )
    }

    @Test
    fun `rejects Latin pronunciation attached to Latin numeric or punctuation only lyrics`() {
        assertNull(
            RomanizationPolicy.sanitize(
                "Where did you go?",
                "yi zei yei yv guong zong",
            )
        )
        assertNull(RomanizationPolicy.sanitize("2026", "er ling er liu"))
        assertNull(RomanizationPolicy.sanitize("...?!", "la la la"))
    }

    @Test
    fun `rejects non Latin pronunciation text`() {
        assertNull(RomanizationPolicy.sanitize("Getting washed", "ゲッティング ウォッシュト"))
        assertNull(RomanizationPolicy.sanitize("君の名は", "きみのなは"))
        assertNull(RomanizationPolicy.sanitize("Home", "回家"))
    }

    @Test
    fun `rejects pronunciation copied from original lyrics`() {
        assertNull(RomanizationPolicy.sanitize("Getting washed", "Getting   washed!"))
        assertNull(RomanizationPolicy.sanitize("<b>Home</b>", "HOME"))
        assertNull(RomanizationPolicy.sanitize("君の名は", "君の名は"))
    }

    @Test
    fun `accepts only explicit Latin script language tags`() {
        assertTrue(RomanizationPolicy.isLatinLanguageTag("ja-Latn"))
        assertTrue(RomanizationPolicy.isLatinLanguageTag("und-latn"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag("ja-Hrkt"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag("zh-Hans"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag(null))
    }

    @Test
    fun `accepts Hangul pronunciations for both English and Japanese supplements`() {
        assertEquals(
            "아이 러브 유",
            RomanizationPolicy.sanitizeSupplement("I love you", "아이 러브 유"),
        )
        assertEquals(
            "키미노 나와",
            RomanizationPolicy.sanitizeSupplement("君の名は", "키미노 나와"),
        )
        assertEquals(
            "아리가토",
            RomanizationPolicy.sanitizeHangulSupplement("ありがとう", "아리가토"),
        )
        assertNull(RomanizationPolicy.sanitize("I love you", "아이 러브 유"))
        assertNull(RomanizationPolicy.sanitizeSupplement("I love you", "アイ ラブ ユー"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("君の名は", "Kimi no na wa"))
        assertNull(RomanizationPolicy.sanitizeSupplement("I love you", "아이 love 유"))
        assertNull(RomanizationPolicy.sanitizeSupplement("안녕하세요", "안녕하세요"))
        assertNull(RomanizationPolicy.sanitizeSupplement("...", "아이 러브 유"))
        assertEquals(
            "Kimi no na wa",
            RomanizationPolicy.sanitizeSupplement("君の名は", "Kimi no na wa"),
        )
        assertTrue(RomanizationPolicy.isHangulPronunciation("키미노 나와"))
        assertFalse(RomanizationPolicy.isHangulPronunciation("kimi no na wa"))
    }

    @Test
    fun `Japanese prolonged vowel mark does not invalidate a Hangul pronunciation`() {
        assertEquals(
            "다링 한파나쿠 라부 토 키라메키 우키아시다츠 피로소피",
            RomanizationPolicy.sanitizeHangulSupplement(
                "ダーリン半端なくラブ！ときらめき浮き足立つフィロソフィ",
                "다링 한파나쿠 라부 토 키라메키 우키아시다츠 피로소피",
            ),
        )
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("한국어ー", "한국어"))
    }

    @Test fun halfwidthKanaAndSoundMarksAreValidOnlyWithARealJapaneseSource() {
        assertEquals("가하로", RomanizationPolicy.sanitizeHangulSupplement(
            "ｶﾞﾊﾛｰ", "가하로",
        ))
        assertEquals("다린", RomanizationPolicy.sanitizeHangulSupplement(
            "ﾀﾞｰﾘﾝ", "다린",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ｰ", "오"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ﾞ", "가"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("한국어ｰ", "한국어"))
    }

    @Test
    fun `isolated Hangul jamo never reaches a visible Japanese pronunciation row`() {
        val broken = listOf("ㄴ", "노ㄴ미소", "노미소", "노ㅏ미소")
        broken.forEach { candidate ->
            assertFalse("compatibility or combining jamo: $candidate",
                RomanizationPolicy.isHangulPronunciation(candidate))
            assertNull(RomanizationPolicy.sanitizeHangulSupplement("脳みそ", candidate))
            assertFalse(RomanizationPolicy.sameHangulSounds(candidate, "노미소"))
        }
        assertEquals("노미소", RomanizationPolicy.sanitizeHangulSupplement("脳みそ", "노미소"))
    }

    @Test
    fun `Japanese nasal can compose orphan consonant into preceding Korean syllable`() {
        // Visible in the supplied IRIS OUT video: バラバラんなる rendered a
        // separate ㄴ at the edge of a wrapped Korean fallback row.
        assertEquals("바라바란 나루", RomanizationPolicy.sanitizeHangulSupplement(
            "バラバラんなる", "바라바라 ㄴ나루",
        ))
        assertEquals("바라바란 나루", RomanizationPolicy.sanitizeSupplement(
            "バラバラんなる", "바라바라 ㄴ나루",
        ))
        // No Japanese ん, no uniquely attachable Korean syllable, and no
        // known coda slot: reject instead of inventing a phonetic sound.
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("バラバラなる", "바라바라 ㄴ나루"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("んなる", "ㄴ나루"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("こんなる", "콘ㄴ나루"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("こんなる", "코ㄴㄴ나루"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("こんにちは", "콘니치와 ㄴ"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("こんにちは", "코ㄴ니치하"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("かん", "카ㄴ 2"))
        // A nasal sound that appears somewhere in the sentence cannot be
        // reassigned to a later Kanji word just because its syllables match.
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("かん、蚊", "카 카ㄴ"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement(
            "かん".repeat(250), "카 ㄴ",
        ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement(
                "やたらとしんどい恋煩いバラバラんなる頭とこの身体",
                "야타라토신도이 코이와즈라이 바라바라 ㄴ나루 아타마토코노카라다",
            ))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("あ、かん、蚊、い", "아 카 카ㄴ 이"))
    }

    @Test fun halfwidthJapaneseNasalRequiresExactWholeKanaSoundBeforeRepair() {
        assertEquals("칸", RomanizationPolicy.sanitizeHangulSupplement("ｶﾝ", "카 ㄴ"))
        assertEquals("간", RomanizationPolicy.sanitizeHangulSupplement("ｶﾞﾝ", "가 ㄴ"))
        assertEquals("칸", RomanizationPolicy.sanitizeSupplement("ｶﾝ", "카 ㄴ"))

        // Never attach a stray consonant to a different source, an unsupported
        // Kanji fragment, or a Hangul syllable which already has a final.
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ｶ", "카 ㄴ"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ﾝｶ", "ㄴ카"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ｶﾝ悪", "카 ㄴ아"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ｶﾝ", "칸 ㄴ"))
        assertNull(RomanizationPolicy.sanitizeHangulSupplement("ｶﾝ", "카ㄴ 2"))
    }
}
