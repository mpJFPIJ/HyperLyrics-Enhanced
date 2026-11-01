package com.juren233.hyperlyricsenhanced.root.aitrans

/** Provider-independent recovery decisions; callers keep credentials inside Enhanced. */
internal object AiTranslationRecoveryPolicy {
    fun readTimeoutMillis(isGoogleGemini: Boolean, lineCount: Int): Int = when {
        isGoogleGemini -> 150_000
        lineCount <= 25 -> 90_000
        else -> 150_000
    }

    fun shouldUseGroqFallback(reason: String, groqAvailable: Boolean): Boolean =
        groqAvailable && (
            reason.startsWith("http_5") || reason == "http_429" ||
                reason == "EOFException" || reason == "network_error" ||
                reason == "retry_exhausted" || reason == "empty_response" ||
                reason == "quality_invalid"
            )

    fun shouldRepairAfterSuccess(needsPronunciation: Boolean, groqAvailable: Boolean): Boolean =
        needsPronunciation && groqAvailable
}
