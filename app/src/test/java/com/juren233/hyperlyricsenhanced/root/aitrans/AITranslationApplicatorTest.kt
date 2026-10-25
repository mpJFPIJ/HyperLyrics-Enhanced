package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class AITranslationApplicatorTest {

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
    fun `adds Japanese romaji without replacing an existing pronunciation`() {
        val song = Song(lyrics = listOf(
            RichLyricLine(text = "君の名は", roma = "Kimi no na wa"),
            RichLyricLine(text = "ありがとう")
        ))
        val updated = AITranslationApplicator.apply(
            song,
            listOf(
                TranslationItem(0, "너의 이름은", "Wrong reading"),
                TranslationItem(1, "고마워", "arigatō")
            ),
            forceOverride = true
        )
        assertEquals("Kimi no na wa", updated.lyrics?.get(0)?.roma)
        assertEquals("arigatō", updated.lyrics?.get(1)?.roma)
        assertEquals("너의 이름은", updated.lyrics?.get(0)?.translation)
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
