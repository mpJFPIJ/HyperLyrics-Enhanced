package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded pronunciation-only fallback on the already configured primary API.
 * Never makes the user supply a second provider key merely to fix missing lines.
 * Does not modify Korean meaning, source timestamps or cached good readings.
 */
internal object PrimaryPronunciationClient {
    private fun prompt(song: Song): String = """
당신은 일본어 노래 원문을 실제 일본어 소리에 맞춰 한글로 표기하는 발음 전문가다.
제목: ${song.name.orEmpty().take(90)}
가수: ${song.artist.orEmpty().take(90)}
한자 독음은 단어와 문맥으로 결정하라. 중국어 한자 독음이나 한국어 뜻으로 읽지 말라.
일본어 조사 は/へ/を, 연탁, 촉음, 장음을 정확히 처리하라.
영어가 섞이면 영어의 실제 발음을 한글로 적고 원문 단어를 빠뜨리지 말라.
이미 한국어인 가사는 절대 재표기하지 말고, 원문 음절을 만들어내지 말라.
trans는 항상 빈 문자열이며 roma에는 발음만 한글로 적는다.
입력 lyrics 배열의 index는 절대 변경하지 마라.
응답: {"translations":[{"index":2,"trans":"","roma":"키미노 나와"}]}
설명이나 Markdown 없이 JSON 객체 하나만 출력하라.
""".trimIndent()

    internal fun accepted(
        payload: TranslationRequest,
        response: List<TranslationItem>,
    ): List<TranslationItem> {
        val originals = payload.lyrics.associate { it.index to it.text }
        return response.filter { item ->
            val source = originals[item.index] ?: return@filter false
            AIPronunciationPolicy.eligibleForeignLine(source) &&
                RomanizationPolicy.sanitizeHangulSupplement(source, item.roma) != null
        }.map { it.copy(trans = "") }
    }

    suspend fun recover(
        configs: AiTranslationConfigs,
        song: Song,
        original: TranslationRequest,
        current: List<TranslationItem>,
    ): List<TranslationItem> = withContext(Dispatchers.IO) {
        if (!configs.isUsable) return@withContext current
        val keys = AiApiKeyPool.orderedForRequest(
            configs.apiKeys.takeIf { it.isNotEmpty() }
                ?: AiApiKeyPool.parse(null, configs.apiKey),
        )
        if (keys.isEmpty()) return@withContext current
        val missing = AIPronunciationPolicy.missingReadingIndices(original.lyrics, current)
        if (missing.isEmpty()) return@withContext current
        val isCohere = CohereNativeChatPolicy.applies(configs)
        val base = configs.baseUrl.orEmpty().trim().trimEnd('/')
        val endpoint = if (isCohere) CohereNativeChatPolicy.endpoint()
            else if (base.endsWith("/chat/completions")) base
            else "$base/chat/completions"
        val isGemini = OpenAiRequestCompatibility.isGoogleGemini(configs.model, base)
        var merged = current
        // Index ranges remain original even when a request contains only
        // previously missing lines. A model returning a partial result is
        // acceptable here; successful lines remain cached.
        for (batch in original.lyrics.filter { it.index in missing }.chunked(16).take(8)) {
            val payload = TranslationRequest(batch)
            val budget = AiTranslationRecoveryPolicy.outputTokenBudget(
                configs.maxTokens, batch.size,
            ).coerceIn(512, 4_096)
            val messages = listOf(
                ChatMessage("system", prompt(song)),
                ChatMessage("user", payload.toJson()),
            )
            val request = OpenAiChatRequest(
                model = configs.model.orEmpty(),
                messages = messages,
                responseFormat = ResponseFormat("json_object").takeUnless {
                    isCohere || isGemini
                },
                maxCompletionTokens = budget.takeUnless { isCohere || isGemini },
                maxTokens = budget.takeIf { isCohere },
            )
            val body = if (isCohere) CohereNativeChatPolicy.requestBody(
                request.model, messages, budget,
            ) else request.toJson()
            var response: String? = null
            for (key in keys.take(2)) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12_000
                        readTimeout = 25_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(body)
                    }
                    val status = conn.responseCode
                    if (status == 200) {
                        response = conn.inputStream.bufferedReader(Charsets.UTF_8)
                            .use { it.readText().take(128 * 1024) }
                        break
                    }
                    LogManager.w("AppleMusicAiService",
                        "stage=primary_phonetics_http code=$status")
                    if (status in 400..499 && status != 429) break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogManager.w("AppleMusicAiService",
                        "stage=primary_phonetics_io type=${error.javaClass.simpleName}")
                } finally {
                    conn?.disconnect()
                }
            }
            val text = response?.let { bodyText ->
                runCatching {
                    if (isCohere) CohereNativeChatPolicy.parseResponse(bodyText).first
                    else json.decodeFromString<OpenAiChatResponse>(bodyText)
                        .choices.firstOrNull()?.message?.content.orEmpty()
                }.getOrNull()
            }.orEmpty()
            val parsed = runCatching {
                AITranslationResponseParser.parse(text, batch.map { it.index }.toSet())
            }.getOrDefault(emptyList())
            val accepted = accepted(payload, parsed)
            LogManager.i(
                "AppleMusicAiService",
                "stage=primary_phonetics_recovery received=${accepted.size}/${batch.size}",
            )
            merged = KoreanLyricOutputPolicy.mergePhonetics(merged, accepted)
        }
        merged
    }
}
