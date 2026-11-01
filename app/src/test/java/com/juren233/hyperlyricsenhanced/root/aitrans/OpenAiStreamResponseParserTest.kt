package com.juren233.hyperlyricsenhanced.root.aitrans

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiStreamResponseParserTest {
    @Test fun joinsContentDeltasAndExposesFinishReason() {
        val input = """
            : keep alive

            data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"{\"translations\":["},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"{\"index\":0,\"trans\":\"안녕\"}]}"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()
        val result = OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
        assertEquals("{\"translations\":[{\"index\":0,\"trans\":\"안녕\"}]}", result.content)
        assertEquals("stop", result.finishReason)
    }

    @Test fun detectsLengthTruncation() {
        val input = """
            data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"length"}]}

            data: [DONE]
        """.trimIndent()
        val result = OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
        assertEquals("partial", result.content)
        assertEquals("length", result.finishReason)
    }

    @Test fun ignoresReasoningAndReturnsEmptyContentWhenOnlyReasoningExists() {
        val input = """
            data: {"choices":[{"delta":{"reasoning_content":"private"},"finish_reason":null}]}

            data: [DONE]
        """.trimIndent()
        val result = OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
        assertEquals("", result.content)
        assertNull(result.finishReason)
    }
}
