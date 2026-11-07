package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy

/**
 * Korean-targeted Apple lyric display only. Online providers and Apple sometimes
 * return Chinese or Japanese translations while an asynchronous Hangul AI result
 * is still being prepared. Never show them as "Korean translation".
 *
 * Keep user-edited lines when explicitly supplied. For automatic sources,
 * prefer a Hangul overlay over an unrelated official translation. Empty state
 * is safer than a misleading non-Korean translation.
 */
internal object KoreanLyricsDisplayPolicy {
    fun select(userCorrection: String?, supplement: String?, official: String?): String? {
        val user = OnlineTranslationContentPolicy.sanitize(userCorrection)
        if (user != null) return user
        val online = OnlineTranslationContentPolicy.sanitize(supplement)
        val native = OnlineTranslationContentPolicy.sanitize(official)
        return listOfNotNull(online, native).firstOrNull(::isKoreanDisplayLine)
    }

    fun isKoreanDisplayLine(text: String): Boolean {
        if (text.any { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u318E' }) return true
        // Permit punctuation-only lines (e.g. music interludes), not foreign text.
        return text.none(Char::isLetter)
    }
}
