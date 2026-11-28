package com.juren233.hyperlyricsenhanced.root.aitrans

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test fun incompleteStreamCannotPassAsACompletedTranslation() {
        val input = """
            data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}

        """.trimIndent()
        try {
            OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
            fail("A stream without the provider's [DONE] marker must be rejected")
        } catch (failure: OpenAiStreamResponseParser.Failure) {
            assertEquals("incomplete_sse", failure.reason)
            assertEquals(1, failure.contentFragments)
        }
    }

    @Test fun expiredDeadlineIsReportedWithoutLeakingGeneratedText() {
        try {
            OpenAiStreamResponseParser.read(
                BufferedReader(StringReader("")), deadlineNanos = System.nanoTime() - 1L,
            )
            fail("Already-expired stream deadlines must fail")
        } catch (failure: OpenAiStreamResponseParser.Failure) {
            assertEquals("sse_deadline", failure.reason)
            assertTrue(failure.message.orEmpty().none { it == '안' })
        }
    }

    @Test fun providerErrorIsCategorizedWithoutReadingPrivateErrorBody() {
        val input = """
            data: {"error":{"message":"private contents"}}

            data: [DONE]
        """.trimIndent()
        try {
            OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
            fail("Provider errors must reject the stream")
        } catch (failure: OpenAiStreamResponseParser.Failure) {
            assertEquals("provider_sse_error", failure.reason)
            assertTrue(!failure.message.orEmpty().contains("private contents"))
        }
    }

    @Test fun reportsOnlyCountersDuringStreaming() {
        val input = """
            data: {"choices":[{"delta":{"reasoning_content":"private reasoning"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"private translation"},"finish_reason":null}]}

            data: [DONE]
        """.trimIndent()
        var progress: OpenAiStreamResponseParser.Progress? = null
        OpenAiStreamResponseParser.read(BufferedReader(StringReader(input))) {
            progress = it
        }
        assertEquals(2, progress?.eventsSeen)
        assertEquals(1, progress?.contentFragments)
        assertEquals(1, progress?.reasoningFragments)
    }

    @Test fun namedSseErrorEventIsRecognizedEvenWithoutJsonErrorField() {
        val input = """
            event: error
            data: {"message":"private provider explanation"}

            data: [DONE]
        """.trimIndent()
        try {
            OpenAiStreamResponseParser.read(BufferedReader(StringReader(input)))
            fail("Named provider error must not be treated as a successful stream")
        } catch (failure: OpenAiStreamResponseParser.Failure) {
            assertEquals("provider_sse_error", failure.reason)
        }
    }
}
