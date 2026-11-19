package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import java.net.URI

/** Models which only translate should not be required to invent pronunciation. */
internal data class AiProviderCapabilities(
    val requestAiPronunciation: Boolean,
    val allowGroqFallback: Boolean,
    val useJsonResponseFormat: Boolean,
    val useCustomSampling: Boolean,
) {
    companion object {
        fun forConfig(configs: AiTranslationConfigs): AiProviderCapabilities {
            val host = runCatching { URI(configs.baseUrl.orEmpty()).host.orEmpty() }.getOrDefault("")
            val isCohere = host.equals("api.cohere.ai", ignoreCase = true) &&
                configs.model.orEmpty().startsWith("north-small-translate", ignoreCase = true)
            return AiProviderCapabilities(
                requestAiPronunciation = !isCohere,
                allowGroqFallback = !isCohere,
                // North uses Cohere's documented native Chat V2 endpoint.
                // Keep the JSON contract in the prompt, not a model-specific
                // OpenAI compatibility response_format parameter.
                useJsonResponseFormat = !isCohere,
                useCustomSampling = !isCohere,
            )
        }
    }
}
