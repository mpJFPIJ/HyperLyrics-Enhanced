package com.juren233.hyperlyricsenhanced.root.aitrans

/**
 * A conservative original-lyrics language guard. Never infer the song's language
 * from translated text, Hangul phonetic supplements, or the system locale.
 */
internal object KoreanSongTranslationPolicy {
    private fun isHangul(c: Char): Boolean =
        c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' ||
            c in '\u3130'..'\u318F' || c in '\uA960'..'\uA97F' ||
            c in '\uD7B0'..'\uD7FF'

    private fun isJapaneseOrHan(c: Char): Boolean =
        c in '\u3040'..'\u30FF' || c in '\u3400'..'\u9FFF' ||
            c in '\uF900'..'\uFAFF' || c in '\uFF66'..'\uFF9F'

    fun isKoreanTarget(target: String?): Boolean =
        target?.trim()?.lowercase() in setOf("한국어", "한국말", "korean", "ko", "ko-kr")

    /** Korean-dominant tracks (including occasional English phrases) need no AI subtitles. */
    fun isKoreanSong(originalLines: List<String>): Boolean {
        val lines = originalLines.filter { it.any(Char::isLetter) }
        if (lines.isEmpty()) return false
        val korean = lines.count { line -> line.any(::isHangul) }
        val japanese = lines.count { line -> line.any(::isJapaneseOrHan) }
        return korean > 0 && korean * 2 >= lines.size && korean > japanese
    }

    /** Do not request Korean-to-Korean paraphrases in mixed-language tracks. */
    fun shouldRequestTranslation(original: String, target: String?): Boolean {
        if (original.none(Char::isLetter)) return false
        if (!isKoreanTarget(target)) return true
        return !(original.any(::isHangul) && original.none(::isJapaneseOrHan))
    }
}
