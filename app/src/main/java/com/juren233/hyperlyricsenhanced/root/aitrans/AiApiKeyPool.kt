package com.juren233.hyperlyricsenhanced.root.aitrans

import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only API credential pool. Keys must never be sent to the patched
 * Apple Music process, diagnostic logs or remote feature preferences.
 */
internal object AiApiKeyPool {
    const val MAX_KEYS = 12
    private const val MAX_KEY_LENGTH = 512
    private val cursor = AtomicLong(0)

    /**
     * One credential per line; duplicate/empty/oversized entries are ignored.
     * The existing single-key preference is used only when no pool was saved.
     */
    fun parse(configured: String?, legacy: String? = null): List<String> {
        val source = configured?.takeIf { it.isNotBlank() } ?: legacy.orEmpty()
        return source.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAX_KEY_LENGTH && it.none(Char::isWhitespace) }
            .distinct()
            .take(MAX_KEYS)
            .toList()
    }

    fun orderedForRequest(keys: List<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val start = ((cursor.getAndIncrement() and Long.MAX_VALUE) % keys.size).toInt()
        return List(keys.size) { index -> keys[(start + index) % keys.size] }
    }
}
