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

    /** NVIDIA NIM supports streamed chat completions; use it to avoid idle read timeouts. */
    fun useNvidiaStreaming(baseUrl: String): Boolean = runCatching {
        java.net.URI(baseUrl).host.equals("integrate.api.nvidia.com", ignoreCase = true)
    }.getOrDefault(false)

    /** The NIM default of 4096 output tokens can truncate multi-line Korean+Hangul JSON. */
    fun outputTokenBudget(configured: Int, lineCount: Int): Int =
        if (configured > 0) configured else (lineCount * 140).coerceIn(4096, 16_000)

    fun streamReadTimeoutMillis(): Int = 45_000

    fun streamDeadlineMillis(): Long = 180_000L
}
