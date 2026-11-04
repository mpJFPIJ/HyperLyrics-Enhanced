package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** AI-reviewed Hangul wins; offline readings fill only absent/invalid phonetics. */
internal object AiLyricComposition {
    fun merge(translations: List<TranslationItem>, localReadings: List<TranslationItem>): List<TranslationItem> {
        if (localReadings.isEmpty()) return translations
        val result = linkedMapOf<Int, TranslationItem>()
        translations.forEach { result[it.index] = it }
        localReadings.forEach { reading ->
            val sound = reading.roma?.takeIf(String::isNotBlank) ?: return@forEach
            val previous = result[reading.index]
            result[reading.index] = if (previous == null) {
                TranslationItem(index = reading.index, roma = sound)
            } else if (RomanizationPolicy.isHangulPronunciation(previous.roma)) {
                previous
            } else previous.copy(roma = sound)
        }
        return result.values.sortedBy(TranslationItem::index)
    }
}
