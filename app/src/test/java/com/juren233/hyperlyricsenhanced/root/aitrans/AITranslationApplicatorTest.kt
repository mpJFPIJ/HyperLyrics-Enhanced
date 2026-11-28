package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricPronunciationSpan
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AITranslationApplicatorTest {

    @Test fun chineseDominantSongRejectsUnverifiedJapaneseReadingsOnChineseRows() {
        val original = Song(lyrics = List(9) { index ->
            RichLyricLine(text = "感谢你曾来过 $index")
        } + RichLyricLine(text = "君の名は"))
        val readings = original.lyrics.orEmpty().mapIndexed { index, _ ->
            TranslationItem(index, roma = if (index == 9) "키미노 나와" else "칸샤니")
        }
        val result = AITranslationApplicator.apply(original, readings)
        assertTrue(result.lyrics.orEmpty().take(9).all { it.roma == null })
        assertEquals("키미노 나와", result.lyrics?.last()?.roma)
    }

    @Test fun appleJapaneseLanguageTagAllowsKanjiOnlyPronunciation() {
        val original = Song(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to "ja-Kore",
            ),
            lyrics = listOf(RichLyricLine(text = "世界")),
        )
        val result = AITranslationApplicator.apply(original, listOf(
            TranslationItem(0, roma = "세카이"),
        ))
        assertEquals("세카이", result.lyrics?.single()?.roma)
    }

    @Test fun appliesMixedJapaneseKoreanPhoneticsOnlyWhenKoreanOriginalIsPreserved() {
        val song = Song(lyrics = listOf(RichLyricLine(text = "君の名は 사랑해")))
        val accepted = AITranslationApplicator.apply(song, listOf(
            TranslationItem(0, "너의 이름은 사랑해", "키미노 나와 사랑해"),
        ))
        assertEquals("키미노 나와 사랑해", accepted.lyrics?.single()?.roma)
        val rejected = AITranslationApplicator.apply(song, listOf(
            TranslationItem(0, "너의 이름은 사랑해", "키미노 나와"),
        ))
        assertEquals(null, rejected.lyrics?.single()?.roma)
    }

    @Test fun replacingAiPronunciationInvalidatesOldWordAndSourceAlignment() {
        val line = RichLyricLine(
            text = "君の名は", roma = "키미노나와",
            pronunciationStatus = "ALIGNED",
            pronunciationWords = listOf(LyricWordPronunciation("君の名は", "키미노나와")),
            pronunciationSpans = listOf(LyricPronunciationSpan(
                0, 4, "君の名は", "키미노나와",
            )),
        )
        val translated = AITranslationApplicator.apply(
            Song(lyrics = listOf(line)),
            listOf(TranslationItem(0, "너의 이름은", "키미노 나와")),
        )
        assertEquals("키미노 나와", translated.lyrics?.single()?.roma)
        assertEquals(null, translated.lyrics?.single()?.pronunciationWords)
        assertEquals(null, translated.lyrics?.single()?.pronunciationSpans)
    }

    @Test fun aiPhoneticsHaveProvenanceIndependentOfTranslation() {
        val song = Song(lyrics = listOf(RichLyricLine(text = "未知語だ")))
        val translated = AITranslationApplicator.apply(
            song, listOf(TranslationItem(0, "알 수 없는 말", "미치고")),
        )
        assertEquals("AI_GENERATED_UNVERIFIED",
            translated.lyrics?.single()?.pronunciationStatus)
        assertEquals("미치고", translated.lyrics?.single()?.roma)
    }


    @Test
    fun `preserves online translations while filling unmatched lines`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(text = "First", translation = "在线译文"),
                RichLyricLine(text = "Second")
            )
        )

        val translated = AITranslationApplicator.apply(
            song = song,
            transItems = listOf(
                TranslationItem(index = 0, trans = "AI 覆盖译文"),
                TranslationItem(index = 1, trans = "AI 补充译文")
            ),
            forceOverride = false
        )

        assertEquals(
            listOf("在线译文", "AI 补充译文"),
            translated.lyrics?.map { it.translation }
        )
    }

    @Test
    fun `renders Japanese phonetics in Hangul while retaining source data`() {
        val song = Song(lyrics = listOf(
            RichLyricLine(text = "君の名は", roma = "Kimi no na wa"),
            RichLyricLine(text = "ありがとう")
        ))
        val updated = AITranslationApplicator.apply(
            song,
            listOf(
                TranslationItem(0, "너의 이름은", "키미노 나와"),
                TranslationItem(1, "고마워", "아리가토"),
            ),
            forceOverride = true
        )
        assertEquals("Kimi no na wa", song.lyrics?.get(0)?.roma)
        assertEquals("키미노 나와", updated.lyrics?.get(0)?.roma)
        assertEquals("아리가토", updated.lyrics?.get(1)?.roma)
        assertEquals("너의 이름은", updated.lyrics?.get(0)?.translation)
    }

    @Test
    fun `rejects generated Latin romaji and leaves existing source unchanged`() {
        val song = Song(lyrics = listOf(RichLyricLine(
            text = "君の名は", roma = "Kimi no na wa",
        )))
        val result = AITranslationApplicator.apply(
            song, listOf(TranslationItem(0, "너의 이름은", "wrong romaji")),
        )
        assertEquals("Kimi no na wa", result.lyrics?.single()?.roma)
    }

    @Test
    fun `adds English Hangul reading and keeps existing translation when reading only`() {
        val song = Song(lyrics = listOf(RichLyricLine(
            text = "I love you", translation = "사랑해",
        )))
        val updated = AITranslationApplicator.apply(
            song,
            listOf(TranslationItem(index = 0, trans = "", roma = "아이 러브 유"))
        )
        assertEquals("사랑해", updated.lyrics?.single()?.translation)
        assertEquals("아이 러브 유", updated.lyrics?.single()?.roma)
    }
}
