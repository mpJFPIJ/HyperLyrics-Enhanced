package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Apple 6.5.3 can offer native ja-Kore as well as the older ja-Latn. */
internal object AppleNativePronunciationLanguagePolicy {
    /** Share the exact official Japanese Hangul tag rule with the host's
     * matchToSystemLyricsScript hook; request, match and explicit selection
     * must not disagree over whether a track is usable. */
    fun firstJapaneseHangul(languages: List<String>): String? = languages.asSequence()
        .map(String::trim).firstOrNull { language ->
            val normalized = language.lowercase(java.util.Locale.ROOT)
            normalized.startsWith("ja-") &&
                (normalized.endsWith("-kore") || normalized.endsWith("-hang"))
        }

    /** Candidate priority is based on the language tags Apple advertises,
     * not on whether its text getter has materialized a particular line yet.
     * Both the system-script matcher and explicit song setter use this order;
     * their downstream text getters independently verify actual content. */
    fun preferredOfficial(languages: List<String>, hangulFirst: Boolean): String? {
        val hangul = firstJapaneseHangul(languages)
        val latin = languages.asSequence().map(String::trim)
            .firstOrNull(RomanizationPolicy::isLatinLanguageTag)
        return if (hangulFirst) hangul ?: latin else latin ?: hangul
    }

    @Suppress("UNUSED_PARAMETER") // retained API; materialized content is verified downstream
    fun select(
        languages: List<String>,
        hangulFirst: Boolean,
        allowNative: Boolean,
        hasLatinText: Boolean,
        supplementLanguage: String?,
    ): String? {
        return if (allowNative) preferredOfficial(languages, hangulFirst) ?: supplementLanguage
            else supplementLanguage
    }
}
