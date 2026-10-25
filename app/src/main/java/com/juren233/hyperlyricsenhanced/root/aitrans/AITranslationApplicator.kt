package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Applies validated translation items back to lyric lines. */
internal object AITranslationApplicator {
    fun apply(
        song: Song,
        transItems: List<TranslationItem>,
        forceOverride: Boolean = false
    ): Song {
        var appliedCount = 0
        val translationsByIndex = transItems.associateBy { it.index }
        val newLyrics = song.lyrics?.mapIndexed { index, line ->
            val transText = translationsByIndex[index]?.trans?.trim()

            val shouldReplaceTranslation = !transText.isNullOrBlank() &&
                (forceOverride || line.translation.isNullOrBlank()) &&
                transText.lowercase() != line.text?.trim()?.lowercase()
            // Never overwrite an existing source's pronunciation with AI output.
            val addedPronunciation = if (line.roma.isNullOrBlank()) {
                RomanizationPolicy.sanitizeSupplement(
                    line.text,
                    translationsByIndex[index]?.roma
                )
            } else null
            if (shouldReplaceTranslation || addedPronunciation != null) {
                if (shouldReplaceTranslation) appliedCount++
                line.copy(
                    translation = if (shouldReplaceTranslation) transText else line.translation,
                    translationWords = if (shouldReplaceTranslation) null else line.translationWords,
                    roma = line.roma?.takeIf(String::isNotBlank) ?: addedPronunciation,
                )
            } else line
        }
        HookLogger.d(
            "AITranslationApplicator",
            "应用翻译结果: song=${song.name}, lines=$appliedCount"
        )
        return song.copy(lyrics = newLyrics)
    }
}

