package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.utils.LogManager
import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** OpenAI-compatible Chat Completions client for lyric translation. */
internal object OpenAiTranslationClient {
    suspend fun request(
        configs: AiTranslationConfigs,
        song: Song? = null,
        texts: List<String>
    ): List<TranslationItem>? = withContext(Dispatchers.IO) {
        if (configs.apiKey.isNullOrBlank()) {
            LogManager.w("AppleMusicAiService", "stage=http_skipped reason=missing_api_key")
            HookLogger.w("OpenAiTranslationClient", "跳过翻译请求: reason=missing_api_key")
            return@withContext null
        }

        val baseUrl = configs.baseUrl?.removeSuffix("/") ?: "https://api.openai.com/v1"
        val apiUrl =
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val requestItems = texts.mapIndexedNotNull { index, text ->
            text.trim().takeIf(::shouldRequestTranslation)?.let {
                TranslationRequestItem(index = index, text = it)
            }
        }
        if (requestItems.isEmpty()) {
            HookLogger.d("OpenAiTranslationClient", "跳过翻译请求: reason=no_translatable_lines")
            return@withContext emptyList()
        }

        val payload = TranslationRequest(lyrics = requestItems)
        val requestIndices = requestItems.map { it.index }.toSet()
        val isGoogleGemini = OpenAiRequestCompatibility.isGoogleGemini(configs.model, baseUrl)
        val chatRequest = OpenAiChatRequest(
            model = configs.model.orEmpty(),
            messages = listOf(
                ChatMessage("system", AITranslationPrompt.build(configs, song)),
                ChatMessage("user", payload.toJson())
            ),
            // Google's officially documented OpenAI-compatible call requires
            // only model + messages. The prompt already specifies the JSON
            // response structure, and our parser accepts fenced JSON as well.
            responseFormat = ResponseFormat("json_object").takeUnless { isGoogleGemini },
            // Gemini 3.6+ deprecates explicit sampling controls. Google's
            // OpenAI-compatible gateway may also reject OpenAI penalties.
            // Leave these absent so the Gemini endpoint uses safe defaults.
            temperature = configs.temperature.takeUnless { isGoogleGemini },
            topP = configs.topP.takeUnless { isGoogleGemini },
            maxTokens = configs.maxTokens.takeIf { it > 0 },
            presencePenalty = configs.presencePenalty.takeUnless { isGoogleGemini },
            frequencyPenalty = configs.frequencyPenalty.takeUnless { isGoogleGemini }
        )
        if (isGoogleGemini) {
            LogManager.i("AppleMusicAiService", "stage=gemini_request_compat model=${configs.model}")
        }

        // Most 503 responses are transient model/backend overloads. Retry them a
        // few times with jitter rather than immediately losing the lyric result.
        // Do not retry 429 without a quota-aware policy, or non-transient 4xx.
        val requestBody = chatRequest.toJson()
        val url = URL(apiUrl)
        for (attempt in 0 until OpenAiHttpRetryPolicy.MAX_ATTEMPTS) {
            var connection: HttpURLConnection? = null
            var retryDelayMs: Long? = null
            try {
                HookLogger.d(
                    "OpenAiTranslationClient",
                    "发送翻译请求: model=${configs.model}, url=$apiUrl, attempt=${attempt + 1}"
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 60 * 1000
                    readTimeout = 3 * (60 * 1000)
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${configs.apiKey}")
                }

                OutputStreamWriter(connection.outputStream).use {
                    it.write(requestBody)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    LogManager.i("AppleMusicAiService", "stage=http_success code=$responseCode")
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val responseObj = json.decodeFromString<OpenAiChatResponse>(responseBody)
                    val content = responseObj.choices.firstOrNull()?.message?.content ?: run {
                        HookLogger.w("OpenAiTranslationClient", "翻译响应为空")
                        return@withContext null
                    }
                    HookLogger.d("OpenAiTranslationClient", "翻译请求完成: code=$responseCode")
                    return@withContext AITranslationResponseParser.parse(content, requestIndices)
                }

                // Never log or persist a provider error body. It may echo private lyrics.
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(8_192) }
                }.getOrNull()
                val reason = OpenAiRequestCompatibility.classifyHttpError(errorBody)
                retryDelayMs = OpenAiHttpRetryPolicy.nextDelayMillis(
                    statusCode = responseCode,
                    attempt = attempt,
                    retryAfter = connection.getHeaderField("Retry-After"),
                    jitterMillis = Random.nextLong(0L, 501L),
                )
                if (retryDelayMs != null) {
                    LogManager.w(
                        "AppleMusicAiService",
                        "stage=http_retry code=$responseCode next_attempt=${attempt + 2} delay_ms=$retryDelayMs"
                    )
                } else {
                    LogManager.w(
                        "AppleMusicAiService",
                        "stage=http_rejected code=$responseCode reason=$reason"
                    )
                    HookLogger.e("OpenAiTranslationClient", "翻译请求失败: code=$responseCode")
                    return@withContext null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: EOFException) {
                LogManager.w("AppleMusicAiService", "stage=http_io_failed type=EOFException")
                HookLogger.w("OpenAiTranslationClient", "翻译连接意外关闭: reason=EOF")
                return@withContext null
            } catch (e: Exception) {
                LogManager.w(
                    "AppleMusicAiService",
                    "stage=http_io_failed type=${e.javaClass.simpleName}"
                )
                HookLogger.e(
                    "OpenAiTranslationClient",
                    "翻译网络请求异常: type=${e.javaClass.simpleName}",
                    e
                )
                return@withContext null
            } finally {
                connection?.disconnect()
            }
            // Delay after disconnecting, so no open connection is held during backoff.
            delay(retryDelayMs ?: return@withContext null)
        }
        null
    }

    private fun shouldRequestTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.any { it.isLetter() }
    }
}



