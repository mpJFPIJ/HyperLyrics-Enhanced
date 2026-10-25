package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/**
 * Narrow enrichment gate: phonetic supplements for Japanese and English only.
 * Other languages keep the existing translation/romanization behavior.
 */
internal object AIPronunciationPolicy {
    fun shouldEnrich(song: Song): Boolean {
        val lines = song.lyrics.orEmpty().mapNotNull { it.text?.trim() }
            .filter(String::isNotBlank)
        return lines.any(::hasJapaneseKana) || (lines.isNotEmpty() &&
            lines.count(::hasEnglishLetters) * 2 >= lines.size &&
            lines.none(::hasNonLatinLetters))
    }

    private fun hasJapaneseKana(text: String): Boolean =
        text.codePoints().anyMatch { code ->
            when (Character.UnicodeScript.of(code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
                else -> false
            }
        }

    private fun hasEnglishLetters(text: String): Boolean =
        text.any { it in 'A'..'Z' || it in 'a'..'z' }

    private fun hasNonLatinLetters(text: String): Boolean =
        text.codePoints().anyMatch { code ->
            Character.isLetter(code) &&
                Character.UnicodeScript.of(code) != Character.UnicodeScript.LATIN
        }
}
