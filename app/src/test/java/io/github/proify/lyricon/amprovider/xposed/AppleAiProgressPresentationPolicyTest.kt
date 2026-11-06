package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAiProgressPresentationPolicyTest {
    @Test fun `zero readings without translation must not claim partial readings`() {
        assertEquals("phonetics_unavailable", AppleAiProgressPresentationPolicy.completionStage(0, 0, 22))
        assertEquals("한글 발음을 만들지 못했어요", AppleAiProgressPresentationPolicy.describe("phonetics_unavailable", 0, 22, 0))
        assertTrue(AppleAiProgressPresentationPolicy.terminal("phonetics_unavailable"))
    }

    @Test fun partialKoreanPhoneticsCannotClaimCompletionWhenTranslationSucceeds() {
        assertEquals(
            "completed_partial",
            AppleAiProgressPresentationPolicy.completionStage(
                translatedLines = 22, pronouncedLines = 17, expectedReadingLines = 22,
            ),
        )
        assertEquals(
            "AI 번역 완료 · 한글 발음 17/22줄",
            AppleAiProgressPresentationPolicy.describe("completed_partial", 17, 22, 0),
        )
        assertTrue(AppleAiProgressPresentationPolicy.terminal("completed_partial"))
    }

    @Test fun trulyCompleteReadingAndTranslationKeepTheirOwnSuccessStates() {
        assertEquals(
            "completed",
            AppleAiProgressPresentationPolicy.completionStage(22, 22, 22),
        )
        assertEquals(
            "phonetics_partial",
            AppleAiProgressPresentationPolicy.completionStage(0, 17, 22),
        )
        assertEquals(
            "phonetics_completed",
            AppleAiProgressPresentationPolicy.completionStage(0, 22, 22),
        )
        assertEquals(
            "가사 결과 수신됨 · 마무리 확인 중",
            AppleAiProgressPresentationPolicy.describe("result_received", 0, 0, 0),
        )
    }
    @Test fun partialPhoneticsDoesNotClaimAllLinesSucceeded() {
        assertEquals(
            "한글 발음 일부 준비됨 · 지원하지 않는 줄 있음",
            AppleAiProgressPresentationPolicy.describe("phonetics_partial", 0, 0, 0),
        )
        assertTrue(AppleAiProgressPresentationPolicy.terminal("phonetics_partial"))
    }

    @Test fun presentsRealBatchPositionWithoutInventingPercentages() {
        assertEquals(
            "AI 응답 대기 중 · 2/5",
            AppleAiProgressPresentationPolicy.describe("requesting", 2, 5, 0),
        )
        assertEquals(
            "AI 응답 수신 중 · 2/5 · 32조각",
            AppleAiProgressPresentationPolicy.describe("streaming", 2, 5, 32),
        )
    }

    @Test fun clearAndTerminalPhasesNeverExposeProviderPayloads() {
        assertEquals("다른 AI로 재시도 중", AppleAiProgressPresentationPolicy.describe("fallback", 0, 0, 0))
        assertEquals("AI 번역 완료", AppleAiProgressPresentationPolicy.describe("completed", 0, 0, 0))
        assertEquals("일본어 발음 분석 중 (기기 내 처리)", AppleAiProgressPresentationPolicy.describe("pronouncing", 0, 0, 0))
        assertEquals("한글 발음 준비 완료 · 번역은 아직 없음", AppleAiProgressPresentationPolicy.describe("phonetics_completed", 0, 0, 0))
        assertEquals("AI 번역 실패", AppleAiProgressPresentationPolicy.describe("failed", 0, 0, 0))
        assertNull(AppleAiProgressPresentationPolicy.describe("cancelled", 0, 0, 0))
        assertNull(AppleAiProgressPresentationPolicy.describe("unknown", 0, 0, 0))
    }
}
