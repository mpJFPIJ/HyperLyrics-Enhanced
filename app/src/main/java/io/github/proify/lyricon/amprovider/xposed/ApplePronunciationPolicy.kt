package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation

internal object ApplePronunciationPolicy {
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /** A guessed Hangul-to-karaoke-cell split is never a valid timed reading. */
    fun verifiedNativeSegments(
        pronunciation: String,
        mainWordTexts: List<String>,
        alignedWords: List<LyricWordPronunciation>?,
    ): List<String>? {
        if (alignedWords == null || mainWordTexts.isEmpty() ||
            alignedWords.size != mainWordTexts.size ||
            !RomanizationPolicy.isHangulPronunciation(pronunciation)
        ) return null
        if (!alignedWords.zip(mainWordTexts).all { (reading, source) ->
                reading.source.trim() == source.trim() &&
                    (reading.hangul.isBlank() ||
                        RomanizationPolicy.isHangulPronunciation(reading.hangul))
            }
        ) return null
        return alignedWords.map { it.hangul }.takeIf {
            RomanizationPolicy.sameHangulSounds(it.joinToString(""), pronunciation) &&
                it.any(String::isNotBlank)
        }
    }

    fun presentationMode(
        pronunciation: String?,
        hasOfficialTimedWords: Boolean,
        nativeWords: List<String>,
        alignedWords: List<LyricWordPronunciation>?,
    ): ApplePronunciationPresentationMode = when {
        pronunciation.isNullOrBlank() -> ApplePronunciationPresentationMode.HIDDEN
        hasOfficialTimedWords -> ApplePronunciationPresentationMode.OFFICIAL
        !RomanizationPolicy.isHangulPronunciation(pronunciation) ->
            ApplePronunciationPresentationMode.LEGACY_LATIN
        verifiedNativeSegments(pronunciation, nativeWords, alignedWords) != null ->
            ApplePronunciationPresentationMode.ALIGNED_WORDS
        else -> ApplePronunciationPresentationMode.LINE_WIDE
    }

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
     * Only display Hangul per karaoke word when its original text and sound have
     * been verified together. A fallback without such a map is approximate, but
     * must still remain fully visible: placing an entire lyric sentence under
     * the first native word silently clips every syllable after its width.
     * Never create synthetic Apple LyricsWord objects or alter their timestamps.
     */
    fun displaySegments(
        pronunciation: String,
        mainWordTexts: List<String>,
        alignedWords: List<LyricWordPronunciation>? = null,
    ): List<String> {
        if (mainWordTexts.isEmpty()) return emptyList()
        val groupedTokens = pronunciation.trim().split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        val tokens = groupedTokens
        if (tokens.isEmpty()) return emptyList()

        if (RomanizationPolicy.isHangulPronunciation(pronunciation)) {
            verifiedNativeSegments(pronunciation, mainWordTexts, alignedWords)?.let { return it }
            // Last-resort display fallback. Prefer intact space-delimited syllable
            // groups; an unspaced reading can only be shared as Hangul syllables.
            // This is visual distribution, not a claim of precise word timing.
            val splittingUnspacedLine = tokens.size == 1 && mainWordTexts.size > 1
            val fallbackTokens = if (splittingUnspacedLine) {
                tokens.single().map(Char::toString)
            } else tokens
            return distributeByNativeWordWeight(
                fallbackTokens, mainWordTexts,
                separator = if (splittingUnspacedLine) "" else " ",
            )
        }

        return distributeByNativeWordWeight(tokens, mainWordTexts)
    }

    private fun distributeByNativeWordWeight(
        tokens: List<String>,
        mainWordTexts: List<String>,
        separator: String = " ",
    ): List<String> {
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
            tokens.subList(tokenStart, tokenEnd).joinToString(separator)
        }
    }

    private fun String.hangulOnly(): String = filter { it in '가'..'힣' }

    fun shouldUseLineTextOnly(pronunciation: String, mainWordCount: Int): Boolean =
        mainWordCount > 0 && RomanizationPolicy.isHangulPronunciation(pronunciation) &&
            pronunciation.trim().split(Regex("\\s+")).count(String::isNotBlank) != mainWordCount

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

internal enum class ApplePronunciationPresentationMode {
    OFFICIAL,
    ALIGNED_WORDS,
    LINE_WIDE,
    LEGACY_LATIN,
    HIDDEN,
}
