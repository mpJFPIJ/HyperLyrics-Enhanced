package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.root.aitrans.JapaneseToHangul
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
        val raw = pronunciation?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val candidate = repairJapaneseNasalCoda(originalText.orEmpty(), raw) ?: raw
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
                    // U+30FC (ー) is a Japanese long-vowel sign, classified as
                    // UnicodeScript.COMMON despite being a letter. It may
                    // appear inside otherwise valid Katakana lyrics.
                    else -> if (code != 0x30FC) return null // never annotate Korean lines
                }
            }
            index += Character.charCount(code)
        }
        return candidate.takeIf { hasForeignLetter }
    }

    /**
     * Some AI transcriptions write a standalone Korean ㄴ for the Japanese
     * moraic nasal ん. For a completely kana-only source line, a full-line
     * independent Japanese reading can verify the resulting Hangul exactly.
     * A matching kana fragment INSIDE a mixed Kanji line cannot prove its
     * source position; the malformed line must instead be re-requested from
     * the pronunciation model, not "repaired" by moving its consonant.
     */
    private fun repairJapaneseNasalCoda(original: String, reading: String): String? {
        if (original.length > 4_096 || reading.length > 4_096 ||
            original.none { it == 'ん' || it == 'ン' } || reading.count { it == 'ㄴ' } != 1
        ) {
            return null
        }
        // Dropping numbers while comparing sounds can silently approve an
        // invented phonetic digit; は/へ require particle context the literal
        // kana converter cannot independently establish.
        if (reading.codePoints().anyMatch { Character.isDigit(it) } ||
            original.any { it == 'は' || it == 'へ' }
        ) return null
        val nasal = reading.indexOf('ㄴ')
        var before = nasal - 1
        while (before >= 0 && reading[before].isWhitespace()) before--
        if (before < 0 || reading[before] !in '가'..'힣') return null
        val syllable = reading[before]
        if ((syllable.code - 0xAC00) % 28 != 0) return null
        val next = reading.getOrNull(nasal + 1)
        if (next != null && !next.isWhitespace() && next !in '가'..'힣') return null
        val repaired = StringBuilder(reading).apply {
            setCharAt(before, (syllable.code + 4).toChar())
            deleteCharAt(nasal)
        }.toString().trim()
        val allKana = original.codePoints().allMatch { codePoint ->
            codePoint in 0x3040..0x30FF || !Character.isLetterOrDigit(codePoint)
        }
        if (!allKana) return null
        val whole = JapaneseToHangul.fromKana(original)?.filter { it in '가'..'힣' }
        return repaired.takeIf {
            whole != null && whole == repaired.filter { char -> char in '가'..'힣' }
        }
    }

    /** A readable Korean phonetic line contains complete precomposed syllables,
     * not standalone compatibility/leading/medial/final jamo (e.g. ㄴ). A
     * partially composed Hangul string rendered as an extra karaoke row can
     * leave a lone consonant below unrelated Japanese text. */
    fun isHangulPronunciation(pronunciation: String?): Boolean {
        val shown = visibleText(pronunciation.orEmpty())
        var hasHangul = false
        var index = 0
        while (index < shown.length) {
            val code = shown.codePointAt(index)
            if (Character.isLetter(code)) {
                if (code !in 0xAC00..0xD7A3) {
                    return false
                }
                hasHangul = true
            }
            index += Character.charCount(code)
        }
        return hasHangul
    }

    /** Compare all sounds, including Hangul jamo; only presentation spaces may differ. */
    fun sameHangulSounds(first: String?, second: String?): Boolean =
        isHangulPronunciation(first) && isHangulPronunciation(second) &&
            first.orEmpty().filterNot(Char::isWhitespace) ==
                second.orEmpty().filterNot(Char::isWhitespace)

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
