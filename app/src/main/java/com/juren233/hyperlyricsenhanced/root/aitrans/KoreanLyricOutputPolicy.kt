package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Script/completeness guard only; semantic accuracy still requires review. */
internal object KoreanLyricOutputPolicy {
    private fun korean(target: String?): Boolean =
        target?.trim()?.lowercase() in
            setOf("한국어", "한국말", "korean", "ko", "ko-kr")

    fun accepts(items: List<TranslationItem>, indices: Set<Int>, target: String?): Boolean {
        if (items.isEmpty()) return false
        if (!korean(target)) return true
        val translated = items.filter { it.index in indices && it.trans.isNotBlank() }
        if (translated.isEmpty() || translated.size * 10 < indices.size * 6) return false
        val koreanLines = translated.count {
            RomanizationPolicy.isHangulPronunciation(it.trans) ||
                it.trans.none(Char::isLetter)
        }
        return koreanLines * 4 >= translated.size * 3
    }

    fun hangulPronunciationCount(items: List<TranslationItem>): Int =
        items.count { RomanizationPolicy.isHangulPronunciation(it.roma) }

    fun needsPronunciationRepair(items: List<TranslationItem>, total: Int): Boolean =
        total > 0 && hangulPronunciationCount(items) * 4 < total

    fun mergePhonetics(translations: List<TranslationItem>, phonetics: List<TranslationItem>):
        List<TranslationItem> {
        val readings = phonetics.filter {
            RomanizationPolicy.isHangulPronunciation(it.roma)
        }.associate { it.index to requireNotNull(it.roma) }
        val indices = translations.map(TranslationItem::index).toSet()
        val result = translations.map { item ->
            if (RomanizationPolicy.isHangulPronunciation(item.roma)) item
            else item.copy(roma = readings[item.index] ?: item.roma)
        }.toMutableList()
        readings.forEach { (index, reading) ->
            if (index !in indices) result.add(TranslationItem(index, "", reading))
        }
        return result
    }
}
