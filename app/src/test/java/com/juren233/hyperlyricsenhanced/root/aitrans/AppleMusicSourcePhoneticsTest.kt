package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicSourcePhoneticsTest {

    @Test fun unknownHiraganaParticleMustNotOverwriteAProvidedContextualReading() {
        val source = "きみへ"
        val incomplete = LocalJapanesePronunciation.generateLine(source) {
            LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        }
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, incomplete.status)
        val provided = RichLyricLine(
            text = source, roma = "키미에", translation = "너에게",
            words = listOf(LyricWord(text = "きみ"), LyricWord(text = "へ")),
        )
        val result = AppleMusicSourcePhonetics.attach(provided, source, incomplete)
        assertEquals("키미에", result.roma)
        assertEquals("AI_FALLBACK_UNVERIFIED", result.pronunciationStatus)
        assertNull(result.pronunciationWords)
        assertEquals("너에게", result.translation)
    }

    @Test fun mixedJapaneseKanaCanUseExactNativeWordsWithoutFakingKanjiBoundaries() {
        val source = "駄目あい"
        val result = AppleMusicSourcePhonetics.attach(
            RichLyricLine(
                text = source,
                words = listOf("駄目", "あ", "い").map { LyricWord(text = it) },
                translation = "안 돼, 사랑",
            ),
            source,
            SourceReadingAssembler.assemble(source, listOf(
                RawReadingToken(0, 2, "ダメ"), RawReadingToken(2, 4, "アイ"),
            )),
        )
        assertEquals("ALIGNED", result.pronunciationStatus)
        assertEquals(listOf("다메", "아", "이"),
            result.pronunciationWords?.map { it.hangul })
        assertEquals("안 돼, 사랑", result.translation)
    }

    @Test fun verifiedFullLineFineReadingUsesNativeWordHighlightWithoutChangingMeaning() {
        val source = "駄目駄目"
        val nativeWords = listOf("駄", "目", "駄", "目")
        val analyzed = SourceReadingAssembler.retryModesForNativeWords(
            source, nativeWords, modeCount = 2,
        ) { mode ->
            if (mode == 0) listOf(RawReadingToken(0, 2, "ダメ"), RawReadingToken(2, 4, "ダメ"))
            else listOf(
                RawReadingToken(0, 1, "ダ"), RawReadingToken(1, 2, "メ"),
                RawReadingToken(2, 3, "ダ"), RawReadingToken(3, 4, "メ"),
            )
        }
        val result = AppleMusicSourcePhonetics.attach(
            RichLyricLine(
                text = source,
                words = nativeWords.map { LyricWord(text = it) },
                roma = "다메다메", translation = "안 돼, 안 돼",
            ), source, analyzed,
        )
        assertEquals("ALIGNED", result.pronunciationStatus)
        assertEquals(listOf("다", "메", "다", "메"),
            result.pronunciationWords?.map { it.hangul })
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
            result.pronunciationSpans?.map { it.start to it.end })
        assertEquals("안 돼, 안 돼", result.translation)
    }

    @Test fun realAiReadingRemainsVisibleWhenOfflinePureKanjiAnalysisFails() {
        val text = "未知語"
        val original = RichLyricLine(
            text = text, roma = "미치고", pronunciationStatus = "AI_GENERATED_UNVERIFIED",
            words = listOf(LyricWord(text = "未"), LyricWord(text = "知"), LyricWord(text = "語")),
        )
        val result = AppleMusicSourcePhonetics.attach(original, text,
            LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED))
        assertEquals("미치고", result.roma)
        assertEquals("AI_FALLBACK_UNVERIFIED", result.pronunciationStatus)
        // Unverified AI reading has no trustworthy mapping to the three
        // native Japanese cells. Preserve the entire sound for one lyric row.
        assertNull(result.pronunciationWords)
    }

    @Test fun unsupportedEnglishSourceKeepsValidAiHangulFallback() {
        val text = "I miss everything about you"
        val line = RichLyricLine(text = text, roma = "아이 미스 에브리싱 어바웃 유",
            translation = "너의 모든 게 그리워")
        val missing = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        val result = AppleMusicSourcePhonetics.attach(line, text, missing)
        assertEquals(line.roma, result.roma)
        assertEquals("AI_FALLBACK_UNVERIFIED", result.pronunciationStatus)
    }

    @Test fun supplementaryHanMixedWithEnglishIsNotMisclassifiedAsEnglishOnly() {
        val source = "𠮟 love"
        val line = RichLyricLine(text = source, roma = "시츠 러브")
        val missing = LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        val result = AppleMusicSourcePhonetics.attach(line, source, missing)
        assertNull(result.roma)
        assertEquals("MISSING_UNSUPPORTED", result.pronunciationStatus)
        assertNull(result.pronunciationWords)
    }

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

    @Test fun splitNativeWordKeepsCompleteReadingOnlyInIndependentLine() {
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

    @Test fun partialSudachiSpansSurviveWhenAnotherJapaneseTokenIsUnknown() {
        val source = "君の名は"
        val reading = "키미노나와"
        val line = RichLyricLine(
            text = source, roma = reading,
            pronunciationStatus = "AI_GENERATED_UNVERIFIED",
            words = listOf(
                LyricWord(text = "君"), LyricWord(text = "の"),
                LyricWord(text = "名は"),
            ),
        )
        val incomplete = SourceReadingAssembler.assemble(
            source, listOf(
                RawReadingToken(0, 1, "キミ"),
                RawReadingToken(1, 2, "ノ"),
                RawReadingToken(2, 3, "*"),
                RawReadingToken(3, 4, "ワ"),
            ),
        )
        val result = AppleMusicSourcePhonetics.attach(line, source, incomplete)
        assertEquals("PARTIAL_SOURCE_ANCHORED", result.pronunciationStatus)
        assertEquals(2, result.pronunciationSpans?.size)
        // The last token is unknown: its inferred word split must not be
        // rendered as though it had verified native karaoke timing.
        assertNull(result.pronunciationWords)
        assertEquals("키미노나와", result.roma)
    }

    @Test fun unknownKanjiInRepeatedWordsKeepsTheAiReadingAsOneNaturalLine() {
        val source = "駄目駄目駄目 脳"
        val line = RichLyricLine(
            text = source, roma = "다메 다메 다메 노",
            pronunciationStatus = "AI_GENERATED_UNVERIFIED",
            words = listOf("駄", "目", "駄", "目", "駄", "目", "脳")
                .map { LyricWord(text = it) },
        )
        val unavailable = LineReadingResult(
            null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED,
        )
        val result = AppleMusicSourcePhonetics.attach(line, source, unavailable)
        assertEquals("다메 다메 다메 노", result.roma)
        assertNull(result.pronunciationWords)
    }

    @Test fun oneKanaAndUnsupportedKanjiCanPreserveAnExistingAiPronunciation() {
        val source = "が悪"
        val line = RichLyricLine(text = source, roma = "가 와루", translation = "나쁜")
        val incomplete = SourceReadingAssembler.assemble(
            source, listOf(
                RawReadingToken(0, 1, "ガ"),
                RawReadingToken(1, 2, "*"),
            ),
        )
        val result = AppleMusicSourcePhonetics.attach(line, source, incomplete)
        assertEquals("가 와루", result.roma)
        assertEquals("AI_FALLBACK_UNVERIFIED", result.pronunciationStatus)
        assertEquals("나쁜", result.translation)
    }

    @Test fun halfwidthKanaWithUnknownKanjiRetainsOnlyUnverifiedAiSoundAndRealSourceAnchors() {
        val source = "ｶﾞ悪"
        val line = RichLyricLine(
            text = source, roma = "가 와루", translation = "나빠",
            words = listOf(LyricWord(text = "ｶﾞ"), LyricWord(text = "悪")),
        )
        val partial = SourceReadingAssembler.assemble(source, listOf(
            RawReadingToken(0, 2, "ガ"), RawReadingToken(2, 3, "*"),
        ))
        assertEquals(LineReadingStatus.MISSING_UNSUPPORTED, partial.status)
        val result = AppleMusicSourcePhonetics.attach(line, source, partial)
        assertEquals("가 와루", result.roma)
        assertEquals("PARTIAL_SOURCE_ANCHORED", result.pronunciationStatus)
        assertEquals(listOf(0 to 2), result.pronunciationSpans?.map { it.start to it.end })
        assertNull(result.pronunciationWords)
        assertEquals("나빠", result.translation)
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
