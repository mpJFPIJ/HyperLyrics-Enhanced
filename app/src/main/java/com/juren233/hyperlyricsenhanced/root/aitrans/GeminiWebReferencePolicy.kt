package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Treat search output as untrusted context, never as a command or a lyric replacement. */
internal object GeminiWebReferencePolicy {
    private const val MAX_REFERENCE_CHARS = 2_000
    private val validModel = Regex("^gemini-[a-zA-Z0-9._-]{1,80}$")

    fun canSearch(configs: AiTranslationConfigs, title: String?, artist: String?): Boolean =
        configs.webSearchEnabled &&
            configs.targetLanguage.orEmpty().trim().lowercase().let { target ->
                target == "한국어" || target == "korean" || target == "ko" ||
                    target.startsWith("ko-")
            } &&
            OpenAiRequestCompatibility.isGoogleGemini(configs.model, configs.baseUrl) &&
            validModel.matches(configs.model.orEmpty()) &&
            !title.isNullOrBlank() && !artist.isNullOrBlank() &&
            !title.equals("Unknown Track", ignoreCase = true) &&
            !artist.equals("Unknown Artist", ignoreCase = true)

    /** An uncited response or a model output without an executed search is not a reference. */
    fun parseGroundedReference(raw: String): String? = runCatching {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return@runCatching null
        val steps = root["steps"] as? JsonArray
        if (steps == null) return@runCatching parseNativeGrounding(root)
        fun hasStep(type: String): Boolean = steps.any { element ->
            ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == type
        }
        if (!hasStep("google_search_call") || !hasStep("google_search_result")) return@runCatching null
        val output = steps.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.takeIf {
                (it["type"] as? JsonPrimitive)?.contentOrNull == "model_output"
            }
        } ?: return@runCatching null
        val parts = output["content"] as? JsonArray ?: return@runCatching null
        val citedText = parts.firstNotNullOfOrNull { element ->
            val part = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val citations = part["annotations"] as? JsonArray ?: return@firstNotNullOfOrNull null
            if (citations.none { citation ->
                    ((citation as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "url_citation"
                }
            ) return@firstNotNullOfOrNull null
            (part["text"] as? JsonPrimitive)?.contentOrNull
        }?.trim() ?: return@runCatching null
        sanitize(citedText)
    }.getOrNull()

    private fun parseNativeGrounding(root: JsonObject): String? {
        val candidates = root["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val metadata = first["groundingMetadata"] as? JsonObject ?: return null
        val chunks = metadata["groundingChunks"] as? JsonArray ?: return null
        if (chunks.none { chunk ->
                val web = (chunk as? JsonObject)?.get("web") as? JsonObject
                val uri = (web?.get("uri") as? JsonPrimitive)?.contentOrNull
                uri?.startsWith("https://") == true
            }
        ) return null
        val content = first["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null
        val text = parts.firstNotNullOfOrNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        } ?: return null
        return sanitize(text.trim())
    }

    private fun sanitize(value: String): String? = value.takeIf {
        it.isNotEmpty() && it.length <= MAX_REFERENCE_CHARS &&
            !it.contains("NO_MATCH", ignoreCase = true)
    }
}
