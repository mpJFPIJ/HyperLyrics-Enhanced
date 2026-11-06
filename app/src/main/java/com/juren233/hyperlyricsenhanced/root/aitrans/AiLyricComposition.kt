package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** The original Japanese text is authoritative: locally derived readings beat unverified AI text. */
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
            } else if (RomanizationPolicy.isHangulPronunciation(sound)) {
                // A language model can output a plausible Korean syllable that is
                // not present in the source lyric. The offline dictionary reads
                // the *actual source text*. When the syllables agree exactly,
                // preserve the model's phrase boundaries instead of merging
                // every Japanese morpheme into one unbroken Hangul word.
                val proposed = previous.roma?.trim()
                val sameVerifiedSounds = proposed != null &&
                    RomanizationPolicy.sameHangulSounds(proposed, sound)
                previous.copy(roma = if (sameVerifiedSounds) proposed else sound)
            } else previous
        }
        return result.values.sortedBy(TranslationItem::index)
    }
}
