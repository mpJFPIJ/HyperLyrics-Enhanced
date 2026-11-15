package com.juren233.hyperlyricsenhanced.root.aitrans

import java.text.Normalizer
import java.util.Locale

/** Convert actual Apple ja-Latn (Hepburn/Kunrei romaji), not Korean meaning. */
internal object JapaneseRomajiToHangul {
    private val sounds = linkedMapOf(
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ",
        "she" to "しぇ", "che" to "ちぇ", "je" to "じぇ",
        "ti" to "てぃ", "di" to "でぃ", "tu" to "つ",
        "si" to "し", "zi" to "じ", "ji" to "じ",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
    )
    private val sorted = sounds.entries.sortedByDescending { it.key.length }

    fun convert(officialRomaji: String): String? {
        if (officialRomaji.isBlank()) return null
        val letters = Normalizer.normalize(
            officialRomaji.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD,
        ).replace(Regex("[\\u0300-\\u036f]"), "")
        if (letters.any { it.isLetter() && it !in 'a'..'z' }) return null
        val tokens = letters.split(Regex("[^a-z]+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return null
        val kana = tokens.map { token -> convertWord(token) ?: return null }.joinToString(" ")
        return JapaneseToHangul.fromKana(kana)
    }

    private fun convertWord(word: String): String? {
        val kana = StringBuilder()
        var index = 0
        while (index < word.length) {
            val ch = word[index]
            val next = word.getOrNull(index + 1)
            if (ch == next && ch !in "aeioun" && ch.isLetter()) {
                kana.append('っ')
                index++
                continue
            }
            if (ch == 'n' && next == 'n') {
                kana.append('ん')
                index++
                continue
            }
            if (ch == 'n' && (next == null || next == '\'' ||
                (next !in "aeiouy" && next != 'n'))) {
                kana.append('ん')
                index += if (next == '\'') 2 else 1
                continue
            }
            val syllable = sorted.firstOrNull { (roman, _) ->
                word.startsWith(roman, index)
            } ?: return null
            kana.append(syllable.value)
            index += syllable.key.length
        }
        return kana.toString()
    }
}
