package com.juren233.hyperlyricsenhanced.common.lyric

import java.util.Locale

object RomanizationPolicy {
    private val htmlTag = Regex("<[^>]*>")
    private val htmlEntity = Regex("&(?:#\\d+|#x[0-9a-fA-F]+|[A-Za-z]+);")

    fun sanitize(originalText: String?, pronunciation: String?): String? {
        val candidate = pronunciation?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val visibleCandidate = visibleText(candidate)
        if (visibleCandidate.isEmpty() || !isLatinRomanization(visibleCandidate)) return null

        val visibleOriginal = visibleText(originalText.orEmpty())
        if (!hasNonLatinLetter(visibleOriginal)) return null

        val normalizedOriginal = comparableText(visibleOriginal)
        val normalizedCandidate = comparableText(visibleCandidate)
        if (normalizedCandidate.isEmpty() || normalizedCandidate == normalizedOriginal) return null
        return candidate
    }

    /**
     * Existing providers may still supply Latin romanization. AI-provided
     * pronunciation uses Korean Hangul for BOTH English and Japanese lyrics.
     */
    fun sanitizeSupplement(originalText: String?, pronunciation: String?): String? =
        sanitize(originalText, pronunciation)
            ?: sanitizeHangulSupplement(originalText, pronunciation)

    /** Validate a Korean-script phonetic rendering without changing legacy Latin tracks. */
    fun sanitizeHangulSupplement(originalText: String?, pronunciation: String?): String? {
        val candidate = pronunciation?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val shown = visibleText(candidate)
        if (!isHangulPronunciation(shown)) return null

        val original = visibleText(originalText.orEmpty())
        if (comparableText(original).isEmpty() ||
            comparableText(original) == comparableText(shown)
        ) return null

        var hasForeignLetter = false
        var index = 0
        while (index < original.length) {
            val code = original.codePointAt(index)
            if (Character.isLetter(code)) {
                when (Character.UnicodeScript.of(code)) {
                    Character.UnicodeScript.LATIN,
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA -> hasForeignLetter = true
                    else -> return null // never annotate original Korean lines
                }
            }
            index += Character.charCount(code)
        }
        return candidate.takeIf { hasForeignLetter }
    }

    fun isHangulPronunciation(pronunciation: String?): Boolean {
        val shown = visibleText(pronunciation.orEmpty())
        var hasHangul = false
        var index = 0
        while (index < shown.length) {
            val code = shown.codePointAt(index)
            if (Character.isLetter(code)) {
                if (Character.UnicodeScript.of(code) != Character.UnicodeScript.HANGUL) {
                    return false
                }
                hasHangul = true
            }
            index += Character.charCount(code)
        }
        return hasHangul
    }

    fun isLatinLanguageTag(language: String?): Boolean =
        language
            ?.trim()
            ?.split('-')
            ?.any { it.equals("Latn", ignoreCase = true) } == true

    private fun isLatinRomanization(text: String): Boolean {
        var hasLatinLetter = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
                    return false
                }
                hasLatinLetter = true
            }
            index += Character.charCount(codePoint)
        }
        return hasLatinLetter
    }

    private fun hasNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (
                Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun visibleText(text: String): String = text
        .replace(htmlTag, " ")
        .replace(htmlEntity, " ")
        .trim()

    private fun comparableText(text: String): String {
        val normalized = text.lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }
}
