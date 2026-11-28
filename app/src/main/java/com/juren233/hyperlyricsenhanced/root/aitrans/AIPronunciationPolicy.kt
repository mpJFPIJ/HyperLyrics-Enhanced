package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy

/**
 * Narrow enrichment gate: phonetic supplements for Japanese and English only.
 * Other languages keep the existing translation/romanization behavior.
 */
internal object AIPronunciationPolicy {
    /** Apple lists available pronunciation languages for the SONG, not each
     * row. Kana inside Chinese-dominant lyrics therefore cannot authorize
     * Japanese readings for unrelated Han-only Chinese rows. Pure Han lyrics
     * with unambiguous Japanese metadata retain their Japanese path. */
    fun isChineseDominant(song: Song): Boolean {
        val lines = song.lyrics.orEmpty().map { it.text.orEmpty() }
        if (lines.none(::hasHan) || !ChineseLyricsPolicy.isFullyChinese(song)) return false
        return lines.any(::hasJapaneseKana) || appleIdentifiesChinese(song) ||
            !appleIdentifiesJapanese(song)
    }

    fun hasJapaneseProvenance(song: Song): Boolean = !isChineseDominant(song) &&
        (appleIdentifiesJapanese(song) || song.lyrics.orEmpty().any {
            hasJapaneseKana(it.text.orEmpty())
        })

    private fun applePronunciationLanguages(song: Song): List<String> = song.metadata
        ?.getString(LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES)
        .orEmpty().split(Regex("[,;\\s]+"))

    private fun appleIdentifiesJapanese(song: Song): Boolean =
        applePronunciationLanguages(song).any { it.equals("ja", true) || it.startsWith("ja-", true) }

    private fun appleIdentifiesChinese(song: Song): Boolean =
        applePronunciationLanguages(song).any { it.equals("zh", true) || it.startsWith("zh-", true) }

    fun shouldEnrich(song: Song): Boolean {
        // Third-party Hangul readings can be very inaccurate. Their presence
        // must not suppress the user-requested AI pronunciation pass.
        // Only the original lyric language determines enrichment eligibility.
        val lines = song.lyrics.orEmpty()
            .mapNotNull { it.text?.trim() }
            .filter(String::isNotBlank)
        val appleJapaneseLanguage = appleIdentifiesJapanese(song)
        // A Japanese song may contain entirely kanji-only lyric lines.
        return lines.any(::hasJapaneseKana) ||
            (appleJapaneseLanguage && lines.any(::hasHan)) ||
            (lines.isNotEmpty() &&
            lines.count(::hasEnglishLetters) * 2 >= lines.size &&
            lines.none(::hasNonLatinLetters))
    }

    /** Do not count punctuation or a Korean original as a missing reading. */
    fun eligibleForeignLine(text: String, chineseDominant: Boolean = false): Boolean {
        // An isolated kana line in an otherwise Chinese lyric does not turn
        // every Han-only Chinese row into Japanese text. Ambiguous Han-only
        // rows are left untouched without authoritative language provenance.
        if (!ChineseLyricsPolicy.permitsOnlineTranslation(text, chineseDominant)) return false
        val letters = text.codePoints().toArray().filter(Character::isLetter)
        if (letters.isEmpty()) return false
        // A Japanese lyric may contain a Korean phrase in the same timed row.
        // That Korean phrase must be preserved by the final reading validator.
        return letters.any {
            when (Character.UnicodeScript.of(it)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.LATIN -> true
                else -> false
            }
        }
    }

    fun missingReadingIndices(
        lines: List<TranslationRequestItem>,
        current: List<TranslationItem>,
        chineseDominant: Boolean = false,
    ): Set<Int> {
        val byIndex = current.associateBy { it.index }
        return lines.asSequence()
            .filter { eligibleForeignLine(it.text, chineseDominant) }
            .filterNot { line ->
                com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
                    .sanitizeHangulSupplement(line.text, byIndex[line.index]?.roma) != null
            }
            .map { it.index }.toSet()
    }

    private fun hasHan(text: String): Boolean =
        text.codePoints().anyMatch { code ->
            Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
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
