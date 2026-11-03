package io.github.proify.lyricon.amprovider.xposed

/** Only provider-independent state and counters cross into Apple Music. */
internal object AppleAiProgressPresentationPolicy {
    fun describe(stage: String, batch: Int, total: Int, fragments: Int): String? {
        val suffix = if (batch > 0 && total > 0) " · ${batch.coerceAtMost(total)}/$total" else ""
        return when (stage) {
            "preparing" -> "AI 번역 준비 중"
            "researching" -> "가사 문맥 확인 중"
            "requesting" -> "AI 응답 대기 중$suffix"
            "streaming" -> "AI 응답 수신 중$suffix · ${fragments.coerceAtLeast(0)}조각"
            "batch_done" -> "AI 번역 처리 중$suffix"
            "fallback" -> "다른 AI로 재시도 중"
            "completed" -> "AI 번역 완료"
            "failed" -> "AI 번역 실패"
            "skipped" -> "AI 번역 건너뜀"
            else -> null
        }
    }

    fun terminal(stage: String): Boolean = stage in setOf("completed", "failed", "skipped")
}
