package com.juren233.hyperlyricsenhanced.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.juren233.hyperlyricsenhanced.utils.LogManager
import com.juren233.hyperlyricsenhanced.IAppleMusicAiTranslator
import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationProvider
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs AI translation in the separately installed Enhanced app, not in Apple Music
 * or SystemUI. This is necessary when NPatch hooks only the Apple Music process.
 *
 * This exported endpoint NEVER exposes the configured API key to the patched app.
 * Each Binder transaction verifies the Android UID belongs to Apple Music.
 */
class AppleMusicAiTranslationService : Service() {
    private companion object {
        const val TAG = "AppleMusicAiService"
        const val MAX_COMPRESSED_BYTES = 768 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val requestLock = Any()
    private var requestJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogManager.i(TAG, "stage=service_created")
        runCatching { AITranslator.init(applicationContext) }
            .onFailure { LogManager.w(TAG, "stage=service_init_failed type=${it.javaClass.simpleName}") }
    }

    override fun onBind(intent: Intent?): IBinder {
        LogManager.i(TAG, "stage=service_bound")
        return endpoint
    }

    private val endpoint = object : IAppleMusicAiTranslator.Stub() {
        override fun cancel() {
            if (!AppleMusicAiTranslationPolicy.isAuthorizedCaller(
                    packageManager.getPackagesForUid(Binder.getCallingUid())
                )
            ) return
            generation.incrementAndGet()
            LogManager.i(TAG, "stage=request_cancelled")
            synchronized(requestLock) {
                requestJob?.cancel()
                requestJob = null
            }
        }

        override fun translate(compressedSong: ByteArray?, receiver: IAppleMusicTranslationReceiver?) {
            val callingUid = Binder.getCallingUid()
            val allowed = AppleMusicAiTranslationPolicy.isAuthorizedCaller(
                packageManager.getPackagesForUid(callingUid)
            )
            if (!allowed) {
                LogManager.w(TAG, "stage=caller_rejected")
                return
            }
            if (receiver == null || compressedSong == null ||
                compressedSong.isEmpty() || compressedSong.size > MAX_COMPRESSED_BYTES
            ) {
                LogManager.w(TAG, "stage=invalid_request")
                return
            }
            LogManager.i(TAG, "stage=request_received bytes=${compressedSong.size}")

            val serial = generation.incrementAndGet()
            synchronized(requestLock) {
                requestJob?.cancel()
                requestJob = scope.launch {
                    try {
                        val prefs = getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
                        val song = json.decodeFromString<Song>(
                            compressedSong.inflate().toString(Charsets.UTF_8)
                        )
                        LogManager.i(TAG, "stage=song_decoded lines=${song.lyrics.orEmpty().size}")
                        val forceOverride = prefs.getBoolean(
                            RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                            RootConstants.DEFAULT_HOOK_AI_TRANS_FORCE_OVERRIDE
                        )
                        val skipExisting = prefs.getBoolean(
                            RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                            RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION
                        )
                        val enabled = prefs.getBoolean(
                            RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
                            RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE
                        )
                        val hasExisting = song.lyrics.orEmpty()
                            .any { !it.translation.isNullOrBlank() }
                        if (!AppleMusicAiTranslationPolicy.shouldTranslate(
                                enabled = enabled,
                                hasLyrics = !song.lyrics.isNullOrEmpty(),
                                hasExistingTranslation = hasExisting,
                                skipExisting = skipExisting,
                                forceOverride = forceOverride,
                            )
                        ) {
                            LogManager.i(
                                TAG, "stage=request_skipped enabled=$enabled " +
                                    "hasLyrics=${!song.lyrics.isNullOrEmpty()} " +
                                    "hasExisting=$hasExisting skipExisting=$skipExisting"
                            )
                            return@launch
                        }

                        val config = translationConfig(prefs)
                        if (!config.isUsable) {
                            LogManager.w(TAG, "stage=config_incomplete")
                            return@launch
                        }
                        LogManager.i(TAG, "stage=translation_started")
                        val translated = AITranslator.translateSongSync(
                            song = song,
                            configs = config,
                            forceOverride = forceOverride,
                        )
                        if (serial != generation.get()) {
                            LogManager.i(TAG, "stage=result_stale")
                            return@launch
                        }
                        val translatedLines = translated.lyrics.orEmpty()
                            .count { !it.translation.isNullOrBlank() }
                        if (translated === song || translatedLines == 0) {
                            LogManager.w(TAG, "stage=no_translation_result")
                            return@launch
                        }
                        val pronouncedLines = translated.lyrics.orEmpty()
                            .count { !it.roma.isNullOrBlank() }
                        LogManager.i(
                            TAG,
                            "stage=translation_completed lines=$translatedLines pronouncedLines=$pronouncedLines"
                        )

                        val result = json.encodeToString(translated)
                            .toByteArray(Charsets.UTF_8).deflate()
                        if (result.size > MAX_COMPRESSED_BYTES ||
                            serial != generation.get()
                        ) return@launch
                        receiver.onOnlineTranslationResult(result)
                        LogManager.i(TAG, "stage=result_sent bytes=${result.size}")
                    } catch (cancelled: CancellationException) {
                        LogManager.i(TAG, "stage=request_cancelled_inflight")
                        throw cancelled
                    } catch (error: Exception) {
                        // Never log the lyrics, request body, or private API credentials.
                        LogManager.w(TAG, "stage=translation_failed type=${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun translationConfig(prefs: SharedPreferences): AiTranslationConfigs {
        val providerName = prefs.getString(
            RootConstants.KEY_HOOK_AI_TRANS_PROVIDER, AiTranslationProvider.OPENAI.name
        ) ?: AiTranslationProvider.OPENAI.name
        val provider = runCatching { AiTranslationProvider.valueOf(providerName) }
            .getOrDefault(AiTranslationProvider.OPENAI)

        return AiTranslationConfigs(
            provider = providerName,
            targetLanguage = prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG,
                RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG,
            ),
            apiKey = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, ""),
            model = prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_MODEL,
                RootConstants.DEFAULT_HOOK_AI_TRANS_MODEL,
            ).orEmpty().ifBlank { provider.model },
            baseUrl = prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_BASE_URL,
                RootConstants.DEFAULT_HOOK_AI_TRANS_BASE_URL,
            ).orEmpty().ifBlank { provider.url },
            prompt = prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_PROMPT,
                RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
            ) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
            temperature = prefs.getFloat(
                RootConstants.KEY_HOOK_AI_TRANS_TEMPERATURE,
                AiTranslationConfigs.DEFAULT_TEMPERATURE,
            ),
            topP = prefs.getFloat(
                RootConstants.KEY_HOOK_AI_TRANS_TOP_P, AiTranslationConfigs.DEFAULT_TOP_P
            ),
            maxTokens = prefs.getInt(
                RootConstants.KEY_HOOK_AI_TRANS_MAX_TOKENS, AiTranslationConfigs.DEFAULT_MAX_TOKENS
            ),
        )
    }

    override fun onDestroy() {
        synchronized(requestLock) { requestJob?.cancel() }
        scope.cancel()
        super.onDestroy()
    }
}
