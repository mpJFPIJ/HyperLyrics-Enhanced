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

    private const val MAX_CHARS = 512 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun read(
        reader: BufferedReader,
        deadlineNanos: Long = System.nanoTime() + 180_000_000_000L,
    ): Completion {
        val content = StringBuilder()
        val event = StringBuilder()
        var finishReason: String? = null
        var done = false

        fun flush() {
            if (event.isEmpty()) return
            val raw = event.toString().trim()
            event.setLength(0)
            if (raw == "[DONE]") {
                done = true
                return
            }
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw IOException("Invalid SSE response")
            if (root.containsKey("error")) throw IOException("SSE provider error")
            val choices = root["choices"] as? JsonArray ?: return
            val first = choices.firstOrNull() as? JsonObject ?: return
            val delta = first["delta"] as? JsonObject
            val fragment = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
            if (!fragment.isNullOrEmpty()) {
                if (content.length + fragment.length > MAX_CHARS) throw IOException("SSE response too large")
                content.append(fragment)
            }
            finishReason = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: finishReason
        }

        while (true) {
            if (System.nanoTime() - deadlineNanos >= 0L) throw IOException("SSE deadline exceeded")
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(line.substringAfter(':').trimStart())
                }
            }
            if (done) break
        }
        if (!done) flush()
        return Completion(content.toString(), finishReason)
    }
}
