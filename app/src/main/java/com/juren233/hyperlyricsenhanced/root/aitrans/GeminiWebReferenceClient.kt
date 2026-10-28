package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Optional, bounded first-pass research. Only cited context reaches the normal translator. */
internal object GeminiWebReferenceClient {
    private const val SEARCH_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val LEGACY_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MAX_RESPONSE_CHARS = 128 * 1024

    suspend fun search(configs: AiTranslationConfigs, song: Song, lines: List<String>): String? =
        withContext(Dispatchers.IO) {
            if (!GeminiWebReferencePolicy.canSearch(configs, song.name, song.artist)) {
                return@withContext null
            }
            val keys = configs.apiKeys.takeIf { it.isNotEmpty() }
                ?: AiApiKeyPool.parse(null, configs.apiKey)
            val key = AiApiKeyPool.orderedForRequest(keys).firstOrNull()
                ?: return@withContext null
            val excerpts = lines.asSequence()
                .map(String::trim).filter(String::isNotEmpty)
                .take(3).map { it.take(100) }.joinToString(" / ")
            val prompt = """
                    Research an existing Korean translation or critical interpretation of this exact song.
                    Title: ${song.name.orEmpty().take(150)}
                    Artist: ${song.artist.orEmpty().take(150)}
                    Original lyric excerpts (for identifying the correct recording): $excerpts
                    Search the web and verify the song/artist match; reject covers, unrelated names,
                    versions with different lyrics, and unverified fan claims. If no reliable match,
                    respond only with NO_MATCH. Otherwise provide under 1200 characters of Korean
                    context on established interpretations, metaphors, names, and ambiguous lines.
                    Never reproduce full copyrighted lyrics or translations. Cite the found source.
                """.trimIndent()
            val model = requireNotNull(configs.model)
            val legacy = model.startsWith("gemini-2.")
            val request = if (legacy) buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                    })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("google_search", buildJsonObject {}) })
                })
            } else buildJsonObject {
                put("model", model)
                put("input", prompt)
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("type", "google_search") })
                })
            }
            val endpoint = if (legacy) "$LEGACY_BASE$model:generateContent" else SEARCH_ENDPOINT
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 25_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(request.toString())
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    LogManager.w("AppleMusicAiService", "stage=web_reference_unavailable code=$code")
                    return@withContext null
                }
                val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    it.readText().take(MAX_RESPONSE_CHARS + 1)
                }
                if (raw.length > MAX_RESPONSE_CHARS) return@withContext null
                GeminiWebReferencePolicy.parseGroundedReference(raw).also { reference ->
                    LogManager.i(
                        "AppleMusicAiService",
                        if (reference == null) "stage=web_reference_not_verified"
                        else "stage=web_reference_verified",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogManager.w(
                    "AppleMusicAiService", "stage=web_reference_failed type=${error.javaClass.simpleName}",
                )
                null
            } finally {
                connection?.disconnect()
            }
        }
}
