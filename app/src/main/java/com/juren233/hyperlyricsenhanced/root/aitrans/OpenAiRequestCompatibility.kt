package com.juren233.hyperlyricsenhanced.root.aitrans

import java.net.URL

/**
 * Gemini's OpenAI-compatible endpoint does not support every OpenAI request
 * field. Keep non-Google providers unchanged; let Gemini choose its model
 * defaults for sampling/penalties instead of sending incompatible values.
 */
internal object OpenAiRequestCompatibility {
    fun isGoogleGemini(model: String?, baseUrl: String?): Boolean {
        if (!model.orEmpty().trim().startsWith("gemini-", ignoreCase = true)) return false
        val host = runCatching { URL(baseUrl).host }.getOrNull()
        return host.equals("generativelanguage.googleapis.com", ignoreCase = true)
    }

    /**
     * Return a safe category only. Never log the remote error body: gateways
     * can echo request content, including private song lyrics and credentials.
     */
    fun classifyHttpError(body: String?): String {
        val message = body.orEmpty().lowercase()
        return when {
            "presence_penalty" in message -> "presence_penalty"
            "frequency_penalty" in message -> "frequency_penalty"
            "temperature" in message -> "temperature"
            "top_p" in message -> "top_p"
            "max_tokens" in message -> "max_tokens"
            "response_format" in message -> "response_format"
            "api_key_invalid" in message -> "api_key_invalid"
            "resource_exhausted" in message || "quota" in message -> "quota"
            "model" in message && ("not found" in message || "not supported" in message) -> "model_unavailable"
            "invalid_argument" in message -> "invalid_argument"
            else -> "unspecified"
        }
    }
}
