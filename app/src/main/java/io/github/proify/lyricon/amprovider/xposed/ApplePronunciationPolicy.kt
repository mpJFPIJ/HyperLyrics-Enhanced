package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

internal object ApplePronunciationPolicy {
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /**
     * Whether a completed Apple lyric model must trigger a presentation refresh.
     *
     * Official Apple data is the primary source, but it can become available after the
     * first lyrics-page presentation. The refresh decision therefore cannot depend only
     * on the third-party fallback store.
     */
    fun shouldRefreshPresentationAfterBuild(
        sourceIsApple: Boolean,
        hasValidOfficialPronunciation: Boolean,
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
    ): Boolean {
        if (!sourceIsApple) return false
        if (hasOnlineTranslation || hasOnlinePronunciation) return true
        return pronunciationSelected && hasValidOfficialPronunciation
    }

    fun wordTrack(
        hasValidOfficialPronunciation: Boolean,
        hasOnlinePronunciation: Boolean,
    ): ApplePronunciationWordTrack = when {
        hasValidOfficialPronunciation -> ApplePronunciationWordTrack.OFFICIAL
        hasOnlinePronunciation -> ApplePronunciationWordTrack.MAIN_LINE_TIMING
        else -> ApplePronunciationWordTrack.HIDDEN
    }

    /**
     * Apple Music 6.5.0 uses the pronunciation word begin as an exact lookup key while
     * composing the two-line karaoke layout. A valid official text/vector is therefore
     * not enough: every visible main-line word must have a matching pronunciation key.
     *
     * When this is false, callers keep the official text but render it on the native main
     * word/time vector. This preserves Apple-source precedence without creating synthetic
     * native words or inheriting an incompatible pronunciation timeline.
     */
    fun hasCompatibleOfficialWordTiming(
        mainWordBegins: List<Int>,
        pronunciationWordBegins: List<Int>,
    ): Boolean {
        val main = mainWordBegins.filter { it >= 0 }.distinct()
        val pronunciation = pronunciationWordBegins.filter { it >= 0 }.toHashSet()
        return main.isNotEmpty() && pronunciation.isNotEmpty() &&
            main.all(pronunciation::contains)
    }

    /**
     * 将整行发音文本按 Apple 原生主句 word 的实际文本权重分配。
     *
     * Preserve whole Hangul phonetic words when the AI's spaces do not match
     * Apple's karaoke cells. Splitting every Hangul syllable by source-text
     * length creates false readings (e.g. 今 = "이", この世で = "마코노세데").
     * When exact word alignment is unavailable, leave some native cells empty
     * instead of inventing incorrect pronunciation boundaries.
     *
     * 这里只生成显示片段，不创建新的 native LyricsWord。实际渲染继续复用主句 word，
     * 因而每个片段都保留 Apple 原生父歌词行、lineId、wordId 与时间轴。
     */
    fun displaySegments(
        pronunciation: String,
        mainWordTexts: List<String>,
    ): List<String> {
        if (mainWordTexts.isEmpty()) return emptyList()
        val groupedTokens = pronunciation.trim().split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        val tokens = groupedTokens
        if (tokens.isEmpty()) return emptyList()

        // A single unspaced Hangul phrase cannot be assigned to one timed
        // native word without making its entire reading flash at that word.
        if (shouldUseLineTextOnly(pronunciation, mainWordTexts.size)) return emptyList()

        if (RomanizationPolicy.isHangulPronunciation(pronunciation)) {
            if (tokens.size == mainWordTexts.size) return tokens
            if (tokens.size < mainWordTexts.size) {
                val slots = MutableList(mainWordTexts.size) { "" }
                tokens.forEachIndexed { index, token ->
                    val target = if (tokens.size == 1) 0 else
                        index * (slots.size - 1) / (tokens.size - 1)
                    slots[target] = token
                }
                return slots
            }
        }

        val measuredWeights = mainWordTexts.map(::pronunciationUnitCount)
        val weights = if (measuredWeights.any { it > 0 }) {
            measuredWeights
        } else {
            List(mainWordTexts.size) { 1 }
        }
        val totalWeight = weights.sum()
        var consumedWeight = 0
        return weights.map { weight ->
            val tokenStart = consumedWeight * tokens.size / totalWeight
            consumedWeight += weight
            val tokenEnd = consumedWeight * tokens.size / totalWeight
            tokens.subList(tokenStart, tokenEnd).joinToString(" ")
        }
    }

    fun shouldUseLineTextOnly(pronunciation: String, mainWordCount: Int): Boolean =
        mainWordCount > 1 && RomanizationPolicy.isHangulPronunciation(pronunciation) &&
            pronunciation.trim().split(Regex("\\s+")).size == 1

    private fun pronunciationUnitCount(text: String): Int {
        val visibleText = text.replace(HTML_TAG, " ").trim()
        if (visibleText.isEmpty()) return 0

        var eastAsianCharacters = 0
        var hasLetterOrDigit = false
        var index = 0
        while (index < visibleText.length) {
            val codePoint = visibleText.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                hasLetterOrDigit = true
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL -> eastAsianCharacters++
                    else -> Unit
                }
            }
            index += Character.charCount(codePoint)
        }
        return eastAsianCharacters.takeIf { it > 0 } ?: if (hasLetterOrDigit) 1 else 0
    }

    fun selectLanguage(
        systemMatch: String?,
        appleLanguages: List<String>,
        onlineFallbackLanguage: String?,
    ): String? =
        systemMatch.normalizedLatinLanguage()
            ?: appleLanguages.firstNotNullOfOrNull { it.normalizedLatinLanguage() }
            ?: onlineFallbackLanguage.normalizedLatinLanguage()

    private fun String?.normalizedLatinLanguage(): String? =
        this?.trim()?.takeIf {
            it.isNotEmpty() && RomanizationPolicy.isLatinLanguageTag(it)
        }

    private val HTML_TAG = Regex("<[^>]*>")
}

internal enum class ApplePronunciationWordTrack {
    OFFICIAL,
    MAIN_LINE_TIMING,
    HIDDEN,
}
