package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.utils.LogManager
import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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

        var connection: HttpURLConnection? = null
        try {
            val url = URL(apiUrl)
        HookLogger.d("OpenAiTranslationClient", "发送翻译请求: model=${configs.model}, url=$apiUrl")

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60 * 1000
                readTimeout = 3 * (60 * 1000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${configs.apiKey}")
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(chatRequest.toJson())
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
                AITranslationResponseParser.parse(content, requestIndices)
            } else {
                // The error body is read only for allowlisted failure classification.
                // Never persist it: providers may echo private lyric/prompt content.
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(8_192) }
                }.getOrNull()
                val reason = OpenAiRequestCompatibility.classifyHttpError(errorBody)
                LogManager.w(
                    "AppleMusicAiService",
                    "stage=http_rejected code=$responseCode reason=$reason"
                )
                HookLogger.e("OpenAiTranslationClient", "翻译请求失败: code=$responseCode")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EOFException) {
            LogManager.w("AppleMusicAiService", "stage=http_io_failed type=EOFException")
            HookLogger.w("OpenAiTranslationClient", "翻译连接意外关闭: reason=EOF")
            null
        } catch (e: Exception) {
            LogManager.w("AppleMusicAiService", "stage=http_io_failed type=${e.javaClass.simpleName}")
            HookLogger.e(
                "OpenAiTranslationClient",
                "翻译网络请求异常: type=${e.javaClass.simpleName}",
                e
            )
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun shouldRequestTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.any { it.isLetter() }
    }
}



