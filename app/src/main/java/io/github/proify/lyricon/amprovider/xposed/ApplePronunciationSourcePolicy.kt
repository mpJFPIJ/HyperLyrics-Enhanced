package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/**
 * Selects the content source without changing Apple's native lyrics, words or timing.
 *
 * Hangul-first is the historical behavior and deliberately remains the default.
 * "Supplement" is the existing merged AI/online overlay (the store cannot always
 * distinguish AI from third-party output), not a claim about the source provider.
 */
internal object ApplePronunciationSourcePolicy {
    data class Selection(val text: String?, val fromSupplement: Boolean)

    fun select(
        official: String?,
        supplement: String?,
        priority: Int,
        hangulOnly: Boolean = false,
        allowAppleNative: Boolean = true,
        allowHangulSupplement: Boolean = true,
    ): Selection {
        // Never display Apple romanization when explicitly disabled. Existing
        // legacy Latin supplements remain available only with native display on.
        val native = official?.takeIf { allowAppleNative && it.isNotBlank() }
        val overlay = supplement?.takeIf { it.isNotBlank() }
            ?.takeIf { allowHangulSupplement || !RomanizationPolicy.isHangulPronunciation(it) }
            ?.takeIf { allowAppleNative || RomanizationPolicy.isHangulPronunciation(it) }

        // When a song has confirmed Hangul overlay, don't leak native ja-Latn
        // romanization into missing rows under the Hangul-first preference.
        // Apple-first and supplement-first continue to honour the user's choice.
        if (hangulOnly && allowHangulSupplement &&
            priority == RootConstants.APPLE_MUSIC_PRONUNCIATION_HANGUL_FIRST
        ) {
            val hangul = overlay?.takeIf(RomanizationPolicy::isHangulPronunciation)
            return Selection(hangul, hangul != null)
        }

        val supplementFirst = when (priority) {
            RootConstants.APPLE_MUSIC_PRONUNCIATION_APPLE_FIRST -> false
            RootConstants.APPLE_MUSIC_PRONUNCIATION_SUPPLEMENT_FIRST -> true
            // Default: preserve the existing Korean phonetic preference.
            else -> RomanizationPolicy.isHangulPronunciation(overlay)
        }
        return when {
            supplementFirst && overlay != null -> Selection(overlay, true)
            native != null -> Selection(native, false)
            overlay != null -> Selection(overlay, true)
            else -> Selection(null, false)
        }
    }
}
