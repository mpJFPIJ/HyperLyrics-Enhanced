package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicSourcePhoneticsTest {
    @Test fun inheritedReadingMustNotHideNewlyVerifiedLocalPronunciation() {
        val line = RichLyricLine(
            text = "駄目", words = listOf(LyricWord(text = "駄目")),
            roma = "다메", translation = "안 돼",
        )
        val reading = SourceReadingAssembler.assemble("駄目", listOf(RawReadingToken(0, 2, "ダメ")))

        val result = AppleMusicSourcePhonetics.attach(line, "駄目", reading)

        assertEquals("다메", result.roma)
        assertEquals("ALIGNED", result.pronunciationStatus)
        assertEquals("다메", result.pronunciationWords?.single()?.hangul)
        assertEquals("안 돼", result.translation)
    }

    @Test fun splitNativeWordKeepsCompleteReadingButRejectsGuessedWordTiming() {
        val line = RichLyricLine(
            text = "駄目", words = listOf(LyricWord(text = "駄"), LyricWord(text = "目")),
            translation = "안 돼",
        )
        val reading = SourceReadingAssembler.assemble("駄目", listOf(RawReadingToken(0, 2, "ダメ")))

        val result = AppleMusicSourcePhonetics.attach(line, "駄目", reading)

        assertEquals("LINE_WIDE", result.pronunciationStatus)
        assertEquals("다메", result.roma)
        assertNull(result.pronunciationWords)
        assertEquals("안 돼", result.translation)
        assertTrue(result.pronunciationSpans.orEmpty().isNotEmpty())
    }

    @Test fun unverifiableSoundsNeverOverrideSourceWithPlausibleFiller() {
        val line = RichLyricLine(text = "未知語", roma = "키코", translation = "모르는 말")
        val missing = SourceReadingAssembler.assemble("未知語", listOf(RawReadingToken(0, 3, "*")))

        val result = AppleMusicSourcePhonetics.attach(line, "未知語", missing)

        assertNull(result.roma)
        assertEquals("MISSING_UNSUPPORTED", result.pronunciationStatus)
        assertEquals("모르는 말", result.translation)
    }
}
