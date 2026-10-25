package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import java.util.Locale

internal object AITranslationPrompt {
    private val DEFAULT_PROMPT = AiTranslationConfigs.USER_PROMPT

    fun build(configs: AiTranslationConfigs, song: Song?): String {
        val target = configs.targetLanguage?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().displayLanguage
        val title = song?.name ?: "Unknown Track"
        val artist = song?.artist ?: "Unknown Artist"
        val prompt = configs.prompt.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT

        val basePrompt = AiTranslationConfigs.getPrompt(target, title, artist, prompt)
        if (song == null || !AIPronunciationPolicy.shouldEnrich(song)) return basePrompt

        return basePrompt + """

# Additional pronunciation output (same API request; independent of translation)
For each Japanese lyric line, add a "roma" field with its spoken pronunciation
in Latin letters using natural Hepburn Japanese romanization.
For each English lyric line, add a "roma" field with a natural Korean Hangul
approximation of the spoken English sounds, not a word-for-word translation.
Pronounce the ORIGINAL lyric, never the translated Korean text.
Do not generate pronunciation for Korean or unrelated languages.
For mixed Japanese-English songs, apply these rules to each line individually.
Keep the original index and "trans" translation field:
{"translations":[{"index":0,"trans":"한국어 번역","roma":"kimi no na wa"}]}
Output only one valid JSON object without Markdown.
""".trimIndent()
    }
}


