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

    /**
     * A key's 1-based input order is stable within the active, normalized pool.
     * Never print or interpolate [credential] in diagnostics.
     */
    internal class KeySlot(
        val credential: String,
        val index: Int,
        val total: Int,
    ) {
        val logLabel: String get() = "key_index=$index key_total=$total"

        // Accidentally logging the object must also never disclose its secret.
        override fun toString(): String = "GroqKeySlot($logLabel)"
    }

    fun orderedSlotsForRequest(configs: AiTranslationConfigs): List<KeySlot> {
        val keys = configured(configs)
        if (keys.isEmpty()) return emptyList()
        val start = ((cursor.getAndIncrement() and Long.MAX_VALUE) % keys.size).toInt()
        return List(keys.size) { offset ->
            val index = (start + offset) % keys.size
            KeySlot(keys[index], index + 1, keys.size)
        }
    }

    /** Keep the existing simple rotation API for other call sites. */
    fun orderedForRequest(configs: AiTranslationConfigs): List<String> =
        orderedSlotsForRequest(configs).map { it.credential }

    fun canRetryTransient(code: Int, attempt: Int, count: Int): Boolean =
        attempt == 0 && count > 1 && code in setOf(500, 502, 503, 504)
}
