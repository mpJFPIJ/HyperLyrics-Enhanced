package com.juren233.hyperlyricsenhanced.root.aitrans

import java.io.BufferedReader
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads SSE delta fragments; never parses partial JSON as the final translation. */
internal object OpenAiStreamResponseParser {
    data class Completion(val content: String, val finishReason: String?)

    /** Safe metadata only. Never place provider text or translated lyrics in this exception. */
    class Failure(
        val reason: String,
        val eventsSeen: Int,
        val contentFragments: Int,
        val reasoningFragments: Int,
    ) : IOException(reason)

    data class Progress(val eventsSeen: Int, val contentFragments: Int, val reasoningFragments: Int)

    private const val MAX_CHARS = 512 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun read(
        reader: BufferedReader,
        deadlineNanos: Long = System.nanoTime() + 180_000_000_000L,
        onProgress: (Progress) -> Unit = {},
    ): Completion {
        val content = StringBuilder()
        val event = StringBuilder()
        var eventType: String? = null
        var finishReason: String? = null
        var done = false
        var eventsSeen = 0
        var contentFragments = 0
        var reasoningFragments = 0

        fun failed(reason: String): Nothing = throw Failure(
            reason, eventsSeen, contentFragments, reasoningFragments,
        )

        fun flush() {
            if (eventType.equals("error", ignoreCase = true)) failed("provider_sse_error")
            eventType = null
            if (event.isEmpty()) return
            val raw = event.toString().trim()
            event.setLength(0)
            if (raw == "[DONE]") {
                done = true
                return
            }
            eventsSeen++
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: failed("invalid_sse")
            if (root.containsKey("error")) failed("provider_sse_error")
            val choices = root["choices"] as? JsonArray ?: return
            val first = choices.firstOrNull() as? JsonObject ?: return
            val delta = first["delta"] as? JsonObject
            val reasoning = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
            if (!reasoning.isNullOrEmpty()) reasoningFragments++
            val fragment = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
            if (!fragment.isNullOrEmpty()) {
                if (content.length + fragment.length > MAX_CHARS) failed("response_too_large")
                content.append(fragment)
                contentFragments++
            }
            finishReason = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: finishReason
            onProgress(Progress(eventsSeen, contentFragments, reasoningFragments))
        }

        while (true) {
            if (System.nanoTime() - deadlineNanos >= 0L) failed("sse_deadline")
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(line.substringAfter(':').trimStart())
                }
                line.startsWith("event:") -> eventType = line.substringAfter(':').trim()
            }
            if (done) break
        }
        if (!done) flush()
        if (!done) failed("incomplete_sse")
        return Completion(content.toString(), finishReason)
    }
}
