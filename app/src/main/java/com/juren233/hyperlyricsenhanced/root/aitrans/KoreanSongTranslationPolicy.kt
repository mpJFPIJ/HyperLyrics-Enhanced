package com.juren233.hyperlyricsenhanced.root.aitrans

/**
 * A conservative original-lyrics language guard. Never infer the song's language
 * from translated text, Hangul phonetic supplements, or the system locale.
 */
internal object KoreanSongTranslationPolicy {
    private fun isHangul(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL

    private fun isJapaneseOrHan(codePoint: Int): Boolean = when (
        Character.UnicodeScript.of(codePoint)
    ) {
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HAN -> true
        else -> false
    }

    fun isKoreanTarget(target: String?): Boolean =
        target?.trim()?.lowercase() in setOf("한국어", "한국말", "korean", "ko", "ko-kr")

    /** Korean/English-only tracks need no Korean-to-Korean subtitles. Mixed Japanese
     * or Han rows must instead reach the per-line translation guard. */
    fun isKoreanSong(originalLines: List<String>): Boolean {
        val lines = originalLines.filter { it.codePoints().anyMatch(Character::isLetter) }
        if (lines.isEmpty()) return false
        val korean = lines.count { line -> line.codePoints().anyMatch(::isHangul) }
        val hasForeignCjk = lines.any { line -> line.codePoints().anyMatch(::isJapaneseOrHan) }
        return !hasForeignCjk && korean > 0 && korean * 2 >= lines.size
    }

    /** Do not request Korean-to-Korean paraphrases in mixed-language tracks. */
    fun shouldRequestTranslation(original: String, target: String?): Boolean {
        if (original.codePoints().noneMatch(Character::isLetter)) return false
        if (!isKoreanTarget(target)) return true
        return !(original.codePoints().anyMatch(::isHangul) &&
            original.codePoints().noneMatch(::isJapaneseOrHan))
    }
}
