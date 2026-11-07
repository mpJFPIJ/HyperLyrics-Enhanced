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

    @Test fun splitNativeWordKeepsCompleteReadingOnOneAnchoredTimedCell() {
        val line = RichLyricLine(
            text = "駄目", words = listOf(LyricWord(text = "駄"), LyricWord(text = "目")),
            translation = "안 돼",
        )
        val reading = SourceReadingAssembler.assemble("駄目", listOf(RawReadingToken(0, 2, "ダメ")))

        val result = AppleMusicSourcePhonetics.attach(line, "駄目", reading)

        assertEquals("SOURCE_GROUPED", result.pronunciationStatus)
        assertEquals("다메", result.roma)
        assertEquals(listOf("다메", ""), result.pronunciationWords?.map { it.hangul })
        assertEquals("안 돼", result.translation)
        assertTrue(result.pronunciationSpans.orEmpty().isNotEmpty())
    }

    @Test fun temporarySudachiFailureDoesNotEraseExistingReadingOfMixedKanaLyric() {
        val source = "ダーリン半端なくラブ！ときらめき浮き足立つフィロソフィ"
        val line = RichLyricLine(text = source, roma = "다링 한파나쿠 라부 토 키라메키 우키아시다츠 피로소피",
            translation = "달링, 말로 다 할 수 없을 만큼 사랑해")
        val incomplete = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)

        val result = AppleMusicSourcePhonetics.attach(line, source, incomplete)

        assertEquals(line.roma, result.roma)
        assertEquals("AI_FALLBACK_UNVERIFIED", result.pronunciationStatus)
        assertEquals(line.translation, result.translation)
    }

    @Test fun unverifiableSoundsNeverOverrideSourceWithPlausibleFiller() {
        val line = RichLyricLine(text = "未知語", roma = "키코", translation = "모르는 말")
        val missing = SourceReadingAssembler.assemble("未知語", listOf(RawReadingToken(0, 3, "*")))

        val result = AppleMusicSourcePhonetics.attach(line, "未知語", missing)

        assertNull(result.roma)
        assertEquals("MISSING_UNSUPPORTED", result.pronunciationStatus)
        assertEquals("모르는 말", result.translation)
    }

    @Test fun aVerifiedAiSpacingIsPreservedEvenIfTheOfflineLineHasNoSpaces() {
        val source = "脳みその中から"
        val line = RichLyricLine(
            text = source,
            roma = "노미소노 나카카라",
            translation = "머릿속에서",
        )
        val local = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 3, "ノーミソ"),
            RawReadingToken(3, 4, "ノ"),
            RawReadingToken(4, 5, "ナカ"),
            RawReadingToken(5, 7, "カラ"),
        ))
        val result = AppleMusicSourcePhonetics.attach(line, source, local)
        assertEquals("노미소노 나카카라", result.roma)
        assertEquals("LINE_WIDE", result.pronunciationStatus)
        assertEquals("머릿속에서", result.translation)
    }

    @Test fun aiSpacesInsideAVerifiedJapaneseWordAreDiscarded() {
        val source = "脳みその中から"
        val line = RichLyricLine(text = source, roma = "노미소노 나 카카라")
        val local = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 3, "ノーミソ"),
            RawReadingToken(3, 4, "ノ"),
            RawReadingToken(4, 5, "ナカ"),
            RawReadingToken(5, 7, "カラ"),
        ))
        assertEquals("노미소노나카카라", AppleMusicSourcePhonetics.attach(line, source, local).roma)
    }

    @Test fun extraUnverifiedHangulJamoCannotPassTheSoundIdentityCheck() {
        val source = "駄目"
        val line = RichLyricLine(text = source, roma = "다메ㅋㅋ")
        val local = SourceReadingAssembler.assemble(source, listOf(RawReadingToken(0, 2, "ダメ")))
        assertEquals("다메", AppleMusicSourcePhonetics.attach(line, source, local).roma)
    }
}
