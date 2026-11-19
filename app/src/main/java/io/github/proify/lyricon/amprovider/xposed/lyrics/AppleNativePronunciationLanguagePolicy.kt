package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Apple 6.5.3 can offer native ja-Kore as well as the older ja-Latn. */
internal object AppleNativePronunciationLanguagePolicy {
    fun select(
        languages: List<String>,
        hangulFirst: Boolean,
        allowNative: Boolean,
        hasLatinText: Boolean,
        supplementLanguage: String?,
    ): String? {
        val hangul = languages.firstOrNull { language ->
            val normalized = language.lowercase(java.util.Locale.ROOT)
            normalized.startsWith("ja-") &&
                (normalized.endsWith("-kore") || normalized.endsWith("-hang"))
        }
        val latin = languages.firstOrNull(RomanizationPolicy::isLatinLanguageTag)
        return when {
            allowNative && hangulFirst && hangul != null -> hangul
            allowNative && hasLatinText && latin != null -> latin
            allowNative && hangul != null -> hangul
            else -> supplementLanguage
        }
    }
}
