package com.juren233.hyperlyricsenhanced.root.aitrans

/** Indexed, optional Hangul readings, never a substitute for translated meaning. */
internal object LocalJapanesePronunciation {
    private val scriptRuns = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\u3005]+|[A-Za-z]+")
    private val englishWords = mapOf(
        "i" to "아이", "you" to "유", "me" to "미", "my" to "마이",
        "we" to "위", "your" to "유어", "love" to "러브", "baby" to "베이비",
        "darling" to "달링", "no" to "노", "yes" to "예스", "oh" to "오",
        "hey" to "헤이", "yeah" to "예", "hello" to "헬로", "goodbye" to "굿바이",
        "forever" to "포에버", "night" to "나이트", "day" to "데이",
        "heart" to "하트", "world" to "월드", "dream" to "드림",
    )

    fun generate(lines: List<String>, japaneseReading: (String) -> String?): List<TranslationItem> =
        lines.mapIndexedNotNull { index, original ->
            val text = original.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val reading = when {
                text.any(::isJapanese) -> if (text.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                    mixedJapaneseEnglishReading(text, japaneseReading)
                } else {
                    japaneseReading(text)?.let(JapaneseToHangul::fromKana)
                }
                text.any(Char::isLetter) && text.all { it.isWhitespace() || it.isLetter() ||
                    it in "!?'\".,-" } -> englishToHangul(text)
                else -> null
            }
            reading?.let { TranslationItem(index = index, roma = it) }
        }

    private fun mixedJapaneseEnglishReading(
        text: String,
        japaneseReading: (String) -> String?,
    ): String? {
        val segments = scriptRuns.findAll(text).toList()
        if (segments.isEmpty() || text.any { it in '\uac00'..'\ud7a3' }) return null
        return segments.map { segment ->
            val word = segment.value
            if (word.any(::isJapanese)) {
                japaneseReading(word)?.let(JapaneseToHangul::fromKana) ?: return null
            } else {
                englishWords[word.lowercase()] ?: return null
            }
        }.joinToString(" ").takeIf(String::isNotBlank)
    }

    private fun englishToHangul(text: String): String? {
        val words = text.lowercase().split(Regex("[^a-z]+"))
            .filter(String::isNotBlank)
        if (words.isEmpty()) return null
        val mapped = words.map { englishWords[it] ?: return null }
        return mapped.joinToString(" ")
    }

    private fun isJapanese(char: Char): Boolean = char in '\u3040'..'\u30ff' ||
        char in '\u3400'..'\u9fff'
}
