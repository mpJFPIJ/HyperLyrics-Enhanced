package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestCompatibilityTest {
    @Test fun googleGeminiRequestUsesGoogleHostAndModel() {
        val googleUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
        assertTrue(OpenAiRequestCompatibility.isGoogleGemini("gemini-3.8-flash", googleUrl))
        assertTrue(OpenAiRequestCompatibility.isGoogleGemini("gemini-3.1-flash-lite", googleUrl))
        assertFalse(OpenAiRequestCompatibility.isGoogleGemini("gemini-3.8-flash", "https://openrouter.ai/api/v1"))
        assertFalse(OpenAiRequestCompatibility.isGoogleGemini("gpt-oss-120b", googleUrl))
        assertFalse(OpenAiRequestCompatibility.isGoogleGemini("gemini-3.8-flash", "https://generativelanguage.googleapis.com.evil.example/v1"))
        assertFalse(OpenAiRequestCompatibility.isGoogleGemini("gemini-3.8-flash", null))
    }

    @Test fun omittedIncompatibleFieldsDoNotAppearInSerializedJson() {
        val request = OpenAiChatRequest(
            model = "gemini-3.8-flash",
            messages = listOf(ChatMessage("user", "Example")),
            responseFormat = ResponseFormat("json_object"),
            temperature = null, topP = null,
            presencePenalty = null, frequencyPenalty = null,
        )
        val encoded = request.toJson()
        assertTrue(encoded.contains("\"model\":\"gemini-3.8-flash\""))
        assertFalse(encoded.contains("temperature"))
        assertFalse(encoded.contains("top_p"))
        assertFalse(encoded.contains("presence_penalty"))
        assertFalse(encoded.contains("frequency_penalty"))
    }

    @Test fun errorClassificationDoesNotEchoRemotePayload() {
        assertEquals("presence_penalty", OpenAiRequestCompatibility.classifyHttpError(
            """{"error":{"message":"Invalid presence_penalty for sensitive song title"}}"""
        ))
        assertEquals("quota", OpenAiRequestCompatibility.classifyHttpError(
            """{"error":{"status":"RESOURCE_EXHAUSTED","message":"quota exceeded"}}"""
        ))
        assertEquals("unspecified", OpenAiRequestCompatibility.classifyHttpError(
            """{"error":{"message":"Sensitive text should not appear in any log"}}"""
        ))
    }
}
