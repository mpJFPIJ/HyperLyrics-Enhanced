package com.juren233.hyperlyricsenhanced.root.aitrans

import android.content.Context
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger

/**
 * SystemUI 进程内的 AI 歌词翻译门面。
 *
 * 职责边界：
 * - [AITranslationKey] 负责生成整首歌级缓存 key。
 * - [AITranslationCache] 负责内存 + SQLite 双级缓存。
 * - [AITranslationScheduler] 负责暴力切歌场景下的 pending/running 调度、同 key 复用和队列淘汰。
 * - [OpenAiTranslationClient] 负责 OpenAI-compatible 网络请求。
 * - [AITranslationResponseParser] 负责 LLM 响应清洗、兼容解析和 index 校验。
 * - [AITranslationApplicator] 负责把有效译文写回 Song。
 */
object AITranslator {
    private const val MAX_CACHE_SIZE = 1000
    private const val MAX_RUNNING_TRANSLATIONS = 3
    private const val MAX_PENDING_TRANSLATIONS = 5

    private val cacheGeneration = AtomicInteger(0)
    // Do not repeat a rate-limited phonetics repair on every playback event.
    private val repairAttempts = AiPhoneticsRepairAttempts(retryDelayMs = 10L * 60L * 1000L)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cache = AITranslationCache(
        maxCacheSize = MAX_CACHE_SIZE,
        generation = cacheGeneration,
        scope = scope
    )
    private val scheduler = AITranslationScheduler(
        cache = cache,
        maxRunning = MAX_RUNNING_TRANSLATIONS,
        maxPending = MAX_PENDING_TRANSLATIONS
    )

    fun init(context: Context) {
        cache.init(context)
        SudachiReadingEngine.init(context)
    }

    suspend fun translateSongSync(
        song: Song,
        configs: AiTranslationConfigs,
        forceOverride: Boolean = false,
        onProgress: ((AiTranslationProgress) -> Unit)? = null,
    ): Song {
        val startingGeneration = cacheGeneration.get()
        // Do not re-translate Korean songs or restore stale Korean-to-Korean AI caches.
        if (KoreanSongTranslationPolicy.isKoreanTarget(configs.targetLanguage) &&
            KoreanSongTranslationPolicy.isKoreanSong(
                song.lyrics.orEmpty().map { it.text.orEmpty() },
            )
        ) {
            onProgress?.invoke(AiTranslationProgress("skipped"))
            return song
        }
        if (configs.isUsable && song.lyrics.isNullOrEmpty()) return song
        val originalLines = song.lyrics.orEmpty().map { it.text?.trim().orEmpty() }
        val groupId = AiCachedSongCatalog.groupId(song.name, song.artist, originalLines)
        val keys = AITranslationKey.deletionKeys(configs, song, originalLines)
        val ticket = cache.writeTicket(AiCacheScope(groupId, keys), song, startingGeneration)
        if (!configs.isUsable) {
            HookLogger.w("AITranslator", "跳过翻译：配置不完整，API Key 或其他配置为空")
            val lines = song.lyrics.orEmpty().map { it.text.orEmpty() }
            onProgress?.invoke(AiTranslationProgress("pronouncing"))
            val local = locallyReadableSong(song)
            // Groq is an independent, optional pronunciation-only provider:
            // a missing/expired Cohere translation key must not suppress it.
            val request = TranslationRequest(lines.mapIndexed { index, text ->
                TranslationRequestItem(index, text)
            })
            val recovered = if (GroqApiKeyPool.configured(configs).isNotEmpty() &&
                AIPronunciationPolicy.shouldEnrich(song)
            ) GroqMultiModelClient.enrichMissingPronunciation(configs, song, request, local)
            else local
            cache.ensureCurrent(ticket)
            return AITranslationApplicator.apply(song, recovered, forceOverride)
        }

        HookLogger.d("AITranslator", "正在翻译：${song.name}（共 ${song.lyrics?.size ?: 0} 行）")
        return try {
            translateSong(song, configs, forceOverride, originalLines, ticket, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HookLogger.e("AITranslator", "翻译过程发生错误", e)
            song
        }
    }

    suspend fun doOpenAiRequest(
        configs: AiTranslationConfigs,
        song: Song? = null,
        texts: List<String>
    ): List<TranslationItem>? = OpenAiTranslationClient.request(configs, song, texts)

    /**
     * Clear just the current song, including the older translation-only cache
     * and its Korean phonetics-enriched variant.
     */
    suspend fun clearSongCache(song: Song, configs: AiTranslationConfigs): Boolean {
        val lines = song.lyrics.orEmpty().map { it.text?.trim().orEmpty() }
        if (song.id.isNullOrBlank() || lines.isEmpty()) return false
        val keys = AITranslationKey.deletionKeys(configs, song, lines)
        val groupId = AiCachedSongCatalog.groupId(song.name, song.artist, lines)
        return cache.removeSong(groupId, keys, ::cancelRemovedSong)
    }

    /** The HyperLyrics settings app uses its own cache, not Apple Music's UI. */
    suspend fun listCachedSongs(): List<AiCachedSong> = cache.listSavedSongs()

    suspend fun deleteCachedSong(groupId: String): Boolean =
        cache.removeSong(groupId, onRemoved = ::cancelRemovedSong)

    private fun cancelRemovedSong(removed: AiCacheScope) {
        scheduler.cancelSong(removed)
        repairAttempts.forget(removed.keys)
    }

    fun clearCache(callback: () -> Unit) {
        HookLogger.d("AITranslator", "清理记录：正在清空所有本地翻译记录（内存+数据库）")
        // A running request from before Clear must not be reused by the next
        // translation of the same key after its cache generation changes.
        scheduler.cancelAll()
        cache.clear(callback)
    }

    internal fun releaseForReload() {
        scheduler.releaseForReload()
        scope.cancel()
    }

    fun cancelActiveRequests() {
        scheduler.cancelAll()
    }

    private suspend fun translateSong(
        song: Song,
        configs: AiTranslationConfigs,
        forceOverride: Boolean,
        originalLines: List<String>,
        ticket: AiCacheWriteTicket,
        onProgress: ((AiTranslationProgress) -> Unit)? = null,
    ): Song {
        val includePronunciation = AIPronunciationPolicy.shouldEnrich(song)
        if (includePronunciation) onProgress?.invoke(AiTranslationProgress("pronouncing"))
        val locallyRead = loadLocalPronunciation(
            song, originalLines, includePronunciation, ticket,
        )
        val songContentId = AITranslationKey.calculate(
            configs, song, originalLines, includePronunciation = includePronunciation,
        )
        val expectedIndices = originalLines.mapIndexedNotNull { index, line ->
            index.takeIf {
                KoreanSongTranslationPolicy.shouldRequestTranslation(line, configs.targetLanguage)
            }
        }.toSet()
        fun usableCache(items: List<TranslationItem>): Boolean =
            KoreanLyricOutputPolicy.acceptsComplete(items, expectedIndices, configs.targetLanguage)
        // Existing translated songs remain usable even when the pronunciation
        // enrichment request fails or the account is temporarily rate-limited.
        // Enrichment is a one-time new cache entry; new songs require one call
        // for Korean translation + Japanese/English pronunciation together.
        val legacyResults = if (includePronunciation) {
            val oldKey = AITranslationKey.calculate(configs, song, originalLines)
            cache.getFromMemory(oldKey) ?: cache.getFromDb(oldKey)
        } else null

        cache.getFromMemory(songContentId)?.takeIf(::usableCache)?.let {
            HookLogger.d("AITranslator", "缓存命中：从内存加载了 ${song.name} 的翻译")
            val restored = maybeRepairCachedPhonetics(
                songContentId, configs, song, originalLines, includePronunciation,
                AiLyricComposition.merge(it, locallyRead), ticket,
            )
            cache.ensureCurrent(ticket)
            return AITranslationApplicator.apply(
                song, AiLyricComposition.merge(
                    mergeWithLegacyTranslations(legacyResults, restored), locallyRead,
                ), forceOverride,
            )
        }

        cache.getFromDb(songContentId)?.takeIf(::usableCache)?.let {
            HookLogger.d("AITranslator", "记录命中：从本地存储加载了 ${song.name} 的翻译")
            val restored = maybeRepairCachedPhonetics(
                songContentId, configs, song, originalLines, includePronunciation,
                AiLyricComposition.merge(it, locallyRead), ticket,
            )
            cache.ensureCurrent(ticket)
            return AITranslationApplicator.apply(
                song, AiLyricComposition.merge(
                    mergeWithLegacyTranslations(legacyResults, restored), locallyRead,
                ), forceOverride,
            )
        }

        HookLogger.d("AITranslator", "正在请求 AI：本地无记录，准备发起在线翻译")
        val apiResults = scheduler.getOrEnqueue(
            AiTranslationJobKey(songContentId, ticket),
            configs, song, originalLines, onProgress,
        ).await()
        cache.ensureCurrent(ticket)
        if (apiResults.isNullOrEmpty()) {
            HookLogger.w("AITranslator", "翻译失败：未能获取到 ${song.name} 的 AI 翻译")
            // A translation network error should never suppress pronunciation
            // already known locally or available from the independent key pool.
            val previous = AiLyricComposition.merge(legacyResults.orEmpty(), locallyRead)
            val recovered = maybeRepairCachedPhonetics(
                songContentId, configs, song, originalLines, includePronunciation,
                previous, ticket,
            )
            cache.ensureCurrent(ticket)
            return AITranslationApplicator.apply(song, recovered, forceOverride)
        }
        val composed = AiLyricComposition.merge(
            mergeWithLegacyTranslations(legacyResults, apiResults), locallyRead,
        )
        // A translation-only provider may return every Korean meaning but zero
        // Hangul readings. Repair each *missing* Japanese/English line separately,
        // rather than leaving it permanently blank just because most lines passed.
        val completed = maybeRepairCachedPhonetics(
            songContentId, configs, song, originalLines, includePronunciation,
            composed, ticket,
        )
        cache.ensureCurrent(ticket)
        return AITranslationApplicator.apply(song, completed, forceOverride)
    }

    private fun locallyReadableSong(song: Song): List<TranslationItem> {
        if (!AIPronunciationPolicy.shouldEnrich(song)) return emptyList()
        return LocalJapanesePronunciation.generate(song.lyrics.orEmpty().map { it.text.orEmpty() })
    }

    private suspend fun loadLocalPronunciation(
        song: Song,
        lines: List<String>,
        includePronunciation: Boolean,
        ticket: AiCacheWriteTicket,
    ): List<TranslationItem> {
        if (!includePronunciation) return emptyList()
        val key = AITranslationKey.pronunciation(song, lines)
        val cached = (cache.getFromMemory(key) ?: cache.getFromDb(key)).orEmpty()
        val knownIndices = cached.filter { item ->
            item.index in lines.indices &&
                com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
                    .sanitizeHangulSupplement(lines[item.index], item.roma) != null
        }.map(TranslationItem::index).toSet()
        val eligibleCount = lines.count(AIPronunciationPolicy::eligibleForeignLine)
        if (knownIndices.size >= eligibleCount) return cached
        val fresh = LocalJapanesePronunciation.generate(lines)
            .filterNot { it.index in knownIndices }
        if (fresh.isEmpty()) return cached
        val combined = AiLyricComposition.merge(cached, fresh)
        cache.saveToDb(key, combined, song, ticket)
        return combined
    }

    /**
     * Keep the successful Korean translation cached, but allow a later visit
     * to repair missing Hangul without re-translating the entire song.
     * This avoids permanently caching a 22-translations/0-pronunciations result.
     */
    private suspend fun maybeRepairCachedPhonetics(
        key: String,
        configs: AiTranslationConfigs,
        song: Song,
        originalLines: List<String>,
        includePronunciation: Boolean,
        cached: List<TranslationItem>,
        ticket: AiCacheWriteTicket,
    ): List<TranslationItem> {
        if (!includePronunciation ||
            AIPronunciationPolicy.missingReadingIndices(
                originalLines.mapIndexed { index, line -> TranslationRequestItem(index, line) },
                cached,
            ).isEmpty() ||
            (GroqApiKeyPool.configured(configs).isEmpty() && !configs.isUsable)
        ) return cached
        val now = System.nanoTime() / 1_000_000L
        cache.ensureCurrent(ticket)
        if (!repairAttempts.claim(key, ticket, now)) return cached
        val request = TranslationRequest(originalLines.mapIndexed { index, line ->
            TranslationRequestItem(index, line)
        })
        val groqPatched = if (GroqApiKeyPool.configured(configs).isNotEmpty()) {
            GroqMultiModelClient.enrichMissingPronunciation(configs, song, request, cached)
        } else cached
        // A single primary-provider API key is enough for a phonetics-only
        // recovery attempt. Cohere's translation-only model does not normally
        // emit roma, so don't permanently strand a few unresolved lyric lines.
        val patched = if (configs.isUsable) PrimaryPronunciationClient.recover(
            configs, song, request, groqPatched,
        ) else groqPatched
        if (patched != cached) {
            cache.saveToDb(key, patched, song, ticket)
        }
        return patched
    }

    private fun mergeWithLegacyTranslations(
        previous: List<TranslationItem>?,
        enriched: List<TranslationItem>,
    ): List<TranslationItem> {
        if (previous.isNullOrEmpty()) return enriched
        val merged = linkedMapOf<Int, TranslationItem>()
        previous.forEach { merged[it.index] = it }
        enriched.forEach { next ->
            val previousItem = merged[next.index]
            merged[next.index] = next.copy(
                trans = next.trans.takeIf(String::isNotBlank)
                    ?: previousItem?.trans.orEmpty(),
                roma = next.roma?.takeIf(String::isNotBlank) ?: previousItem?.roma,
            )
        }
        return merged.values.toList()
    }
}

/** A late callback cannot claim the retry window of a newer visit after deletion. */
internal class AiPhoneticsRepairAttempts(private val retryDelayMs: Long) {
    private data class Attempt(val generation: Int, val epoch: Long, val at: Long)
    private val lastByKey = mutableMapOf<String, Attempt>()

    @Synchronized fun claim(key: String, ticket: AiCacheWriteTicket, now: Long): Boolean {
        val last = lastByKey[key]
        if (last != null) {
            if (ticket.generation < last.generation) return false
            if (ticket.generation == last.generation) {
                if (ticket.epoch < last.epoch) return false
                if (ticket.epoch == last.epoch && now - last.at < retryDelayMs) return false
            }
        }
        lastByKey[key] = Attempt(ticket.generation, ticket.epoch, now)
        return true
    }

    @Synchronized fun forget(keys: Set<String>) {
        keys.forEach(lastByKey::remove)
    }
}
