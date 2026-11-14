package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.md5
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs

internal object AITranslationKey {
    private const val LEGACY_OFFLINE_READING_REVISION = "sudachi-v1-20260723-korean-phonetics-v1"
    private const val PREVIOUS_SOURCE_OFFSET_REVISION = "sudachi-v1-20260723-full-line-offsets-v2"

    /** A current-song delete must not leave a previous offline reading in the DB. */
    fun deletionKeys(configs: AiTranslationConfigs, song: Song, lines: List<String>): Set<String> = setOf(
        calculate(configs, song, lines, includePronunciation = false),
        calculate(configs, song, lines, includePronunciation = true),
        pronunciation(song, lines),
        pronunciation(song, lines, policyRevision = PREVIOUS_SOURCE_OFFSET_REVISION),
        pronunciation(song, lines, policyRevision = LEGACY_OFFLINE_READING_REVISION),
    )

    fun calculate(configs: AiTranslationConfigs, song: Song, lines: List<String>, includePronunciation: Boolean = false): String {
        return buildString {
            append("target=").appendLine(configs.targetLanguage.orEmpty())
            append("provider=").appendLine(configs.baseUrl.orEmpty().trim().trimEnd('/').lowercase())
            append("model=").appendLine(configs.model.orEmpty().trim().lowercase())
            append("prompt=").appendLine(configs.prompt)
            // Prevent old low-quality Chinese-biased prompts from poisoning the new translation.
            appendLine("translation=ko-native-editor-v4-original-language-guard")
            // Invalidate older unreviewed phonetic results without deleting user data.
            if (includePronunciation) appendLine("pronunciation=ja-hangul-en-hangul-v5-kanji-review")
            if (configs.webSearchEnabled) appendLine(
                if (GroqApiKeyPool.configured(configs).isEmpty()) "web-reference=free-2.5flash-v2"
                else "web-reference=groq-gpt-oss-120b-v3"
            )
            if (GroqApiKeyPool.configured(configs).isNotEmpty()) appendLine("fallback=gpt-oss-120b-then-qwen-v2")
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }.md5()
    }

    /** Independent of remote model/keys: offline readings only depend on source lyrics. */
    fun pronunciation(
        song: Song,
        lines: List<String>,
        policyRevision: String = "sudachi-v1-20260723-full-line-offsets-v3-native-anchors-retry",
    ): String = buildString {
        appendLine(policyRevision)
        append("title=").appendLine(song.name.orEmpty())
        append("artist=").appendLine(song.artist.orEmpty())
        lines.forEachIndexed { index, line -> append(index).append(':').appendLine(line) }
    }.md5()
}



