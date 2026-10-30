package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import java.util.concurrent.atomic.AtomicLong

/** Independent Groq cursor; Gemini pool rotation does not affect this one. */
internal object GroqApiKeyPool {
    private val cursor = AtomicLong(0)

    fun configured(configs: AiTranslationConfigs): List<String> =
        AiApiKeyPool.parse(
            configs.groqApiKeys.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            configs.groqApiKey,
        )

    fun orderedForRequest(configs: AiTranslationConfigs): List<String> {
        val keys = configured(configs)
        if (keys.isEmpty()) return emptyList()
        val start = ((cursor.getAndIncrement() and Long.MAX_VALUE) % keys.size).toInt()
        return List(keys.size) { index -> keys[(start + index) % keys.size] }
    }

    fun canRetryTransient(code: Int, attempt: Int, count: Int): Boolean =
        attempt == 0 && count > 1 && code in setOf(500, 502, 503, 504)
}
