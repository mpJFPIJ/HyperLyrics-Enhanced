package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation

/** Indexed, optional Hangul readings, never a substitute for translated meaning. */
internal object LocalJapanesePronunciation {
    /** Canonical reading for new work: source offsets always refer to the whole line. */
    fun generateLine(
        text: String,
        analyzer: (String) -> LineReadingResult = SudachiReadingEngine::analyzeLine,
    ): LineReadingResult {
        if (text.isBlank()) return LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        if (containsJapaneseSource(text)) {
            val result = analyzer(text)
            if (result.status == LineReadingStatus.COMPLETE) return result
            return kanaOnlyFallback(text) ?: result
        }
        val latin = Regex("[A-Za-z]+").findAll(text).map { match ->
            RawReadingToken(match.range.first, match.range.last + 1, match.value)
        }.toList()
        return SourceReadingAssembler.assemble(text, latin)
    }

    /** Pure kana remains readable even if the Sudachi dictionary is unavailable. */
    private fun kanaOnlyFallback(source: String): LineReadingResult? {
        val segments = Regex("[\\u3040-\\u30FF\\uFF66-\\uFF9F]+")
            .findAll(source).toList()
        if (segments.isEmpty()) return null
        // Without morphology は and へ may be pronounced ワ/エ as particles,
        // not ハ/ヘ as ordinary kana. A guessed COMPLETE result would
        // overwrite a correct provider reading and acquire native clocks.
        // Only the explicitly known greeting has independently proven ワ.
        if (segments.any { match ->
                match.value != "こんにちは" &&
                    match.value.any { it == 'は' || it == 'へ' }
            }
        ) return null
        val covered = BooleanArray(source.length)
        segments.forEach { match ->
            for (index in match.range) covered[index] = true
        }
        // A supplementary Han character occupies two UTF-16 Char values;
        // neither surrogate half is a Char letter. Never declare a kana-only
        // reading complete after silently dropping that source code point.
        var offset = 0
        while (offset < source.length) {
            val codePoint = Character.codePointAt(source, offset)
            val end = offset + Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint) &&
                (offset until end).any { !covered[it] }
            ) return null
            offset = end
        }
        val tokens = segments.map { match ->
            RawReadingToken(
                match.range.first, match.range.last + 1,
                if (match.value == "こんにちは") "コンニチワ" else match.value,
            )
        }
        return SourceReadingAssembler.assemble(source, tokens)
            .takeIf { it.status == LineReadingStatus.COMPLETE }
    }

    fun generate(lines: List<String>): List<TranslationItem> = lines.mapIndexedNotNull { index, line ->
        generateLine(line).text?.let { TranslationItem(index = index, roma = it) }
    }

    private data class SourceRun(val text: String, val japanese: Boolean)

    /** Regex BMP character classes silently skip supplementary Han. Split
     * on complete code points so a Japanese glyph is never dropped while
     * transcribing the English words in the same source lyric. */
    private fun sourceRuns(text: String): List<SourceRun>? {
        val result = mutableListOf<SourceRun>()
        var start = 0
        var kind = 0 // 0=separator, 1=Japanese, 2=ASCII English
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            val nextKind = when {
                isJapaneseCodePoint(codePoint) -> 1
                codePoint in 'a'.code..'z'.code ||
                    codePoint in 'A'.code..'Z'.code -> 2
                else -> 0
            }
            // Unsupported letters and digits are not safe to omit from an
            // otherwise complete Japanese+English pronunciation.
            if (nextKind == 0 && Character.isLetterOrDigit(codePoint)) return null
            if (nextKind != kind) {
                if (kind != 0) result += SourceRun(text.substring(start, offset), kind == 1)
                start = offset
                kind = nextKind
            }
            offset += Character.charCount(codePoint)
        }
        if (kind != 0) result += SourceRun(text.substring(start), kind == 1)
        return result.takeIf { it.isNotEmpty() }
    }
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
                containsJapaneseSource(text) -> if (text.any { it in 'A'..'Z' || it in 'a'..'z' }) {
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

    /**
     * Karaoke timing comes from original Apple words, not AI-invented space boundaries.
     * Only send per-word readings if the concatenated sound equals the selected AI line.
     * On disagreement, let Apple display the whole line instead of placing false syllables.
     */
    fun alignedWords(
        words: List<String>,
        linePronunciation: String,
        japaneseReading: (String) -> String?,
    ): List<LyricWordPronunciation>? {
        if (words.isEmpty() || !RomanizationPolicy.isHangulPronunciation(linePronunciation)) return null
        val aligned = words.filter(String::isNotBlank).map { original ->
            val result = generate(listOf(original), japaneseReading).firstOrNull()?.roma
                ?: if (original.none(Char::isLetter)) "" else return null
            LyricWordPronunciation(original, result)
        }
        if (aligned.isEmpty()) return null
        fun compactHangul(text: String): String = text.filter { it in '가'..'힣' }
        val assembled = compactHangul(aligned.joinToString("") { it.hangul })
        return aligned.takeIf {
            assembled.isNotEmpty() && assembled == compactHangul(linePronunciation)
        }
    }

    private fun mixedJapaneseEnglishReading(
        text: String,
        japaneseReading: (String) -> String?,
    ): String? {
        val segments = sourceRuns(text) ?: return null
        return segments.map { segment ->
            val word = segment.text
            if (segment.japanese) {
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

    private fun isJapaneseCodePoint(codePoint: Int): Boolean =
        codePoint in 0x3040..0x30FF || codePoint in 0xFF66..0xFF9F ||
            codePoint == 0x3005 ||
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    private fun containsJapaneseSource(text: String): Boolean {
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (isJapaneseCodePoint(codePoint)) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }
}
