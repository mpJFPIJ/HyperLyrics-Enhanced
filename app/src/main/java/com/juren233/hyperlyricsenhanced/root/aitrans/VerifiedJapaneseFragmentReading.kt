package com.juren233.hyperlyricsenhanced.root.aitrans

/**
 * A source token may cross Apple native clocks or measured visual rows.
 * Only an independently reproducible kana boundary can prove the syllables
 * belonging to each fragment. If every fragment except one is literal kana,
 * the remaining Kanji fragment inherits the *verified* residual syllables.
 *
 * No syllable counts, character widths, or time fractions are estimated.
 */
internal object VerifiedJapaneseFragmentReading {
    fun split(fragments: List<String>, fullSound: String): List<String>? {
        if (fragments.isEmpty() || fragments.any(String::isEmpty) ||
            fullSound.isEmpty() || fullSound.any { it !in '가'..'힣' } ||
            // A word/row cut at か|゙愛 is not a kana reading boundary. The
            // Han residual must not license the preceding plain か clock.
            fragments.any { JapaneseToHangul.isJapaneseVoicingMark(it.codePointAt(0)) }
        ) return null

        val literal = fragments.map { fragment ->
            if (fragment.any { !JapaneseToHangul.isLiteralKanaCodePoint(it.code) }) null
            else JapaneseToHangul.fromKana(fragment)
                ?.filter { it in '가'..'힣' }
                ?.takeIf(String::isNotEmpty)
        }
        if (literal.all { it != null }) {
            return literal.filterNotNull().takeIf { it.joinToString("") == fullSound }
        }

        val unknown = literal.indices.filter { literal[it] == null }
        if (unknown.size != 1 || !hasJapaneseHan(fragments[unknown.single()])
        ) return null

        val index = unknown.single()
        val prefix = literal.take(index).joinToString("") { it.orEmpty() }
        val suffix = literal.drop(index + 1).joinToString("") { it.orEmpty() }
        if (!fullSound.startsWith(prefix) || !fullSound.endsWith(suffix) ||
            fullSound.length <= prefix.length + suffix.length
        ) return null

        val residual = fullSound.substring(prefix.length, fullSound.length - suffix.length)
        return literal.mapIndexed { cell, sound ->
            if (cell == index) residual else sound ?: return null
        }.takeIf { it.joinToString("") == fullSound }
    }

    private fun hasJapaneseHan(text: String): Boolean {
        var offset = 0
        while (offset < text.length) {
            val point = Character.codePointAt(text, offset)
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN ||
                point == '々'.code
            ) return true
            offset += Character.charCount(point)
        }
        return false
    }
}
