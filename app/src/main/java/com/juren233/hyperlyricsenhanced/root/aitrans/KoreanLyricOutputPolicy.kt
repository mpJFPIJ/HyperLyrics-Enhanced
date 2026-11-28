package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Script/completeness guard only; semantic accuracy still requires review. */
internal object KoreanLyricOutputPolicy {
    private fun korean(target: String?): Boolean =
        target?.trim()?.lowercase() in
            setOf("한국어", "한국말", "korean", "ko", "ko-kr")

    /** A translated Korean sentence may keep Latin names or English song phrases.
     * Pronunciation validation is intentionally stricter and must not be reused here. */
    private fun isKoreanMeaning(text: String): Boolean {
        var hangulSyllables = 0
        var latinLetters = 0
        var foreignLetters = 0
        text.codePoints().forEach { codePoint ->
            if (!Character.isLetter(codePoint)) return@forEach
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HANGUL -> {
                    if (codePoint in 0xAC00..0xD7A3) hangulSyllables++
                }
                Character.UnicodeScript.LATIN -> latinLetters++
                else -> foreignLetters++
            }
        }
        // A Korean sentence can retain an English name, but a foreign-language
        // sentence with one Korean syllable is not a complete Korean translation.
        return hangulSyllables > 0 && hangulSyllables >= foreignLetters &&
            hangulSyllables.toLong() * 3 >= latinLetters
    }

    fun accepts(items: List<TranslationItem>, indices: Set<Int>, target: String?): Boolean {
        if (items.isEmpty()) return false
        if (!korean(target)) return true
        val translated = items.filter { it.index in indices && it.trans.isNotBlank() }
        if (translated.isEmpty() || translated.size * 10 < indices.size * 6) return false
        val koreanLines = translated.count {
            isKoreanMeaning(it.trans) ||
                it.trans.codePoints().noneMatch(Character::isLetter)
        }
        return koreanLines * 4 >= translated.size * 3
    }

    /** Only complete, valid results may be persisted for a new song request. */
    fun acceptsComplete(items: List<TranslationItem>, indices: Set<Int>, target: String?): Boolean {
        if (indices.isEmpty()) return false
        val byIndex = items.filter { it.index in indices }.associateBy(TranslationItem::index)
        return byIndex.size == indices.size &&
            indices.all { !byIndex[it]?.trans.isNullOrBlank() } &&
            accepts(items, indices, target)
    }

    fun hangulPronunciationCount(items: List<TranslationItem>): Int =
        items.count { RomanizationPolicy.isHangulPronunciation(it.roma) }

    fun needsPronunciationRepair(items: List<TranslationItem>, total: Int): Boolean =
        total > 0 && hangulPronunciationCount(items) < total

    fun mergePhonetics(
        translations: List<TranslationItem>,
        phonetics: List<TranslationItem>,
        originals: Map<Int, String> = emptyMap(),
    ):
        List<TranslationItem> {
        fun validated(index: Int, reading: String?): String? {
            val original = originals[index]
            return if (original != null) RomanizationPolicy.sanitizeHangulSupplement(original, reading)
            else reading?.takeIf { RomanizationPolicy.isHangulPronunciation(it) }
        }
        val readings = phonetics.mapNotNull { item ->
            validated(item.index, item.roma)?.let { item.index to it }
        }.toMap()
        val indices = translations.map(TranslationItem::index).toSet()
        val result = translations.map { item ->
            if (validated(item.index, item.roma) != null) item
            else item.copy(roma = readings[item.index] ?: item.roma)
        }.toMutableList()
        readings.forEach { (index, reading) ->
            if (index !in indices) result.add(TranslationItem(index, "", reading))
        }
        return result
    }
}
