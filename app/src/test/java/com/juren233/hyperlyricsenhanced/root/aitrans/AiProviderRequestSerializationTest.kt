package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderRequestSerializationTest {
    @Test fun nvidiaRequestIncludesStreamAndOutputBudget() {
        val body = OpenAiChatRequest(
            model = "mistralai/mistral-large-4-1t-a52b",
            messages = listOf(ChatMessage("user", "Translate JSON")),
            stream = true,
            maxTokens = 6_000,
        ).toJson()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":6000"))
        assertFalse(body.contains("reasoning_format"))
    }

    @Test fun groqQwenHasHiddenReasoningButGptOssDoesNot() {
        val messages = listOf(ChatMessage("user", "Give JSON"))
        val qwen = OpenAiChatRequest(
            model = GroqMultiModelClient.SECONDARY_FALLBACK_MODEL,
            messages = messages,
            responseFormat = ResponseFormat("json_object"),
            reasoningFormat = GroqMultiModelClient.reasoningFormatFor(
                GroqMultiModelClient.SECONDARY_FALLBACK_MODEL,
            ),
            maxCompletionTokens = 6000,
        ).toJson()
        val gpt = OpenAiChatRequest(
            model = GroqMultiModelClient.FALLBACK_MODEL,
            messages = messages,
            responseFormat = ResponseFormat("json_object"),
            reasoningFormat = GroqMultiModelClient.reasoningFormatFor(
                GroqMultiModelClient.FALLBACK_MODEL,
            ),
        ).toJson()
        assertTrue(qwen.contains("\"reasoning_format\":\"hidden\""))
        assertTrue(qwen.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(qwen.contains("\"max_completion_tokens\":6000"))
        assertFalse(gpt.contains("reasoning_format"))
    }
}
