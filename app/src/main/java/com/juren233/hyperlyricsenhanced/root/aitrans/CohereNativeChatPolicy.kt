package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** North Small Translate's documented Chat V2 transport, independent of API credentials. */
internal object CohereNativeChatPolicy {
    fun applies(configs: AiTranslationConfigs): Boolean {
        val uri = runCatching { URI(configs.baseUrl.orEmpty()) }.getOrNull()
        return uri != null && uri.scheme.equals("https", true) &&
            uri.host.equals("api.cohere.ai", true) &&
            configs.model.equals("north-small-translate-1.0", true)
    }

    fun endpoint(): String = "https://api.cohere.ai/v2/chat"

    fun requestBody(model: String, messages: List<ChatMessage>, maxTokens: Int?): String =
        NativeChatRequest(model, messages, maxTokens).toJson()

    fun parseResponse(body: String): Pair<String, String?> {
        val result = json.decodeFromString<NativeChatResponse>(body)
        return result.message.content
            .asSequence()
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("") to result.finishReason
    }

    @Serializable
    private data class NativeChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @SerialName("max_tokens") val maxTokens: Int? = null,
    )

    @Serializable
    private data class NativeChatResponse(
        val message: NativeChatMessage,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class NativeChatMessage(val content: List<NativeContent>)

    @Serializable
    private data class NativeContent(val type: String, val text: String? = null)
}
