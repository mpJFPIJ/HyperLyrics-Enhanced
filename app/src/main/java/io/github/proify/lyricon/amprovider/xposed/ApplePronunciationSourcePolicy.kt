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

    fun select(official: String?, supplement: String?, priority: Int): Selection {
        val native = official?.takeIf { it.isNotBlank() }
        val overlay = supplement?.takeIf { it.isNotBlank() }

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
