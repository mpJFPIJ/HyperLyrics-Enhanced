package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import kotlinx.serialization.Serializable

/** One bounded second opinion for Japanese kanji readings, without rewriting translations. */
internal object JapanesePronunciationReviewPolicy {
    private const val MAX_REVIEW_LINES = 24

    @Serializable
    data class ReviewLine(val index: Int, val original: String, val currentRoma: String)

    fun candidates(lines: List<TranslationRequestItem>): List<TranslationRequestItem> {
        // A Han-only Chinese song must never be mistaken for a Japanese song.
        if (lines.none { line -> line.text.codePoints().anyMatch(::isJapaneseKana) }) {
            return emptyList()
        }
        return lines.filter { line ->
            line.text.codePoints().anyMatch(::isHan) &&
                line.text.codePoints().noneMatch(::isHangul)
        }.take(MAX_REVIEW_LINES)
    }

    fun merge(
        existing: List<TranslationItem>,
        reviewed: List<TranslationItem>,
        allowed: Set<Int>,
        originals: Map<Int, String>,
    ): List<TranslationItem> {
        val byIndex = reviewed.associateBy(TranslationItem::index)
        return existing.map { item ->
            val original = originals[item.index] ?: return@map item
            if (item.index !in allowed) return@map item
            val proposed = RomanizationPolicy.sanitizeHangulSupplement(
                original, byIndex[item.index]?.roma,
            ) ?: return@map item
            val originalSize = original.count(Char::isLetter)
            val currentSize = item.roma.orEmpty().count(Char::isLetter)
            val proposedSize = proposed.count(Char::isLetter)
            val upperBound = if (currentSize > 0) currentSize * 2 + 4 else originalSize * 3 + 4
            val lowerBound = if (currentSize > 0) (currentSize / 2).coerceAtLeast(1) else 1
            if (proposedSize !in lowerBound..upperBound) return@map item
            item.copy(roma = proposed)
        }
    }

    private fun isJapaneseKana(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    private fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    private fun isHangul(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL
}
