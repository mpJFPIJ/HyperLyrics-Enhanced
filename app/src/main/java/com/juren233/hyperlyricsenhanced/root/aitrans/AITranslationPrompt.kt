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
For BOTH Japanese and English ORIGINAL lyric lines, include the "roma" field
as a natural Korean Hangul phonetic transcription of how the ORIGINAL line
is sung or spoken. "roma" is a legacy field name: its value MUST be Hangul,
NOT Latin-script Japanese romaji, Hepburn, English spelling, or IPA.
Examples:
Japanese "君の名は" -> "키미노 나와" (NOT "kimi no na wa")
Japanese "ありがとう" -> "아리가토"
English "I love you" -> "아이 러브 유" (NOT "사랑해")
"trans" is the Korean MEANING translation and "roma" is Korean-script SOUND.
They must be separate even if the translated meaning is already in Korean.
Do not generate pronunciation for original Korean or unrelated languages.
For mixed Japanese-English songs, apply the appropriate reading per line.
Keep the original index and "trans" field. Example:
{"translations":[{"index":0,"trans":"너의 이름은","roma":"키미노 나와"}]}
Output only one valid JSON object without Markdown.
""".trimIndent()
    }
}


