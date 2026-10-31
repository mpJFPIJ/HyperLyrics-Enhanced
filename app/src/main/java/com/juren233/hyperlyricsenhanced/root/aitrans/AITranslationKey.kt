package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.md5
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs

internal object AITranslationKey {
    fun calculate(configs: AiTranslationConfigs, song: Song, lines: List<String>, includePronunciation: Boolean = false): String {
        return buildString {
            append("target=").appendLine(configs.targetLanguage.orEmpty())
            if (includePronunciation) appendLine("pronunciation=ja-hangul-en-hangul-v4-accurate-reading")
            if (configs.webSearchEnabled) appendLine(
                if (GroqApiKeyPool.configured(configs).isEmpty()) "web-reference=free-2.5flash-v2"
                else "web-reference=groq-gpt-oss-120b-v3"
            )
            if (GroqApiKeyPool.configured(configs).isNotEmpty()) appendLine("fallback=qwen3.8-27b-v1")
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }.md5()
    }
}



