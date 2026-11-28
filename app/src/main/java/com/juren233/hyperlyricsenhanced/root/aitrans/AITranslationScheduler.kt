package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Keeps a pre-deletion request separate from a fresh visit to the same song. */
internal data class AiTranslationJobKey(
    val contentId: String,
    val ticket: AiCacheWriteTicket,
)

/** Bounded background scheduler for AI requests during aggressive song switching. */
internal class AITranslationScheduler(
    private val cache: AITranslationCache,
    private val maxRunning: Int,
    private val maxPending: Int
) {
    private companion object {
        const val TAG = "AITranslationScheduler"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val jobs = ConcurrentHashMap<AiTranslationJobKey, TranslationJob>()
    private val pending = ArrayDeque<TranslationJob>()
    private var running = 0

    fun getOrEnqueue(
        key: AiTranslationJobKey,
        configs: AiTranslationConfigs,
        song: Song,
        originalLines: List<String>,
        onProgress: ((AiTranslationProgress) -> Unit)? = null,
    ): Deferred<List<TranslationItem>?> {
        synchronized(lock) {
            jobs[key]?.let {
                if (onProgress != null) it.onProgress = onProgress
                HookLogger.d(TAG, "复用翻译任务: song=${song.name}, key=${key.contentId}")
                return it.deferred
            }

            val job = TranslationJob(
                key = key,
                songName = song.name.orEmpty(),
                configs = configs,
                song = song,
                originalLines = originalLines,
                onProgress = onProgress,
            )
            jobs[key] = job
            pending.addLast(job)
            HookLogger.d(
                TAG,
                "加入翻译队列: song=${job.songName}, pending=${pending.size}, running=$running"
            )
            trimPendingLocked()
            dispatchNextLocked()
            return job.deferred
        }
    }

    fun cancelPending() {
        synchronized(lock) {
            while (pending.isNotEmpty()) {
                val job = pending.removeFirst()
                job.state = TranslationJobState.CANCELLED
                jobs.remove(job.key, job)
                job.deferred.complete(null)
                HookLogger.d(TAG, "取消等待中的翻译任务: song=${job.songName}")
            }
        }
    }

    /** Pending output may depend on a legacy key even if its own cache row does not exist. */
    fun cancelSong(removed: AiCacheScope) = cancelMatching { job ->
        val scope = job.key.ticket.scope
        scope.groupId == removed.groupId || scope.keys.any(removed.keys::contains)
    }

    private fun cancelMatching(matches: (TranslationJob) -> Boolean) {
        val toCancel = synchronized(lock) {
            pending.removeAll { job ->
                if (!matches(job)) return@removeAll false
                job.state = TranslationJobState.CANCELLED
                jobs.remove(job.key, job)
                job.deferred.complete(null)
                true
            }
            jobs.values.mapNotNull { job ->
                if (!matches(job) || job.state != TranslationJobState.RUNNING)
                    return@mapNotNull null
                job.state = TranslationJobState.CANCELLED
                jobs.remove(job.key, job)
                running = (running - 1).coerceAtLeast(0)
                job.deferred.cancel(CancellationException("Song translation cleared"))
                job.coroutineJob
            }.also { dispatchNextLocked() }
        }
        toCancel.forEach { it.cancel() }
    }

    fun releaseForReload() {
        cancelAll()
        scope.cancel()
    }

    fun cancelAll() {
        val runningJobs = synchronized(lock) {
            while (pending.isNotEmpty()) {
                val job = pending.removeFirst()
                job.state = TranslationJobState.CANCELLED
                jobs.remove(job.key, job)
                job.deferred.complete(null)
            }
            jobs.values.mapNotNull { job ->
                if (job.state != TranslationJobState.RUNNING) return@mapNotNull null
                job.state = TranslationJobState.CANCELLED
                jobs.remove(job.key, job)
                running = (running - 1).coerceAtLeast(0)
                job.deferred.cancel(CancellationException("AI translation cancelled"))
                job.coroutineJob
            }
        }
        runningJobs.forEach { it.cancel() }
    }

    private fun trimPendingLocked() {
        while (pending.size > maxPending) {
            val dropped = pending.removeFirst()
            if (dropped.state != TranslationJobState.PENDING) continue

            dropped.state = TranslationJobState.CANCELLED
            jobs.remove(dropped.key, dropped)
            dropped.deferred.complete(null)
            HookLogger.w(TAG, "翻译队列已满: action=drop, song=${dropped.songName}")
        }
    }

    private fun dispatchNextLocked() {
        while (running < maxRunning && pending.isNotEmpty()) {
            val job = pending.removeLast()
            if (job.state != TranslationJobState.PENDING) continue

            job.state = TranslationJobState.RUNNING
            running++
            HookLogger.d(
                TAG,
                "启动翻译任务: song=${job.songName}, pending=${pending.size}, running=$running"
            )
            job.coroutineJob = scope.launch { runJob(job) }
        }
    }

    private suspend fun runJob(job: TranslationJob) {
        try {
            cache.ensureCurrent(job.key.ticket)
            val apiResults =
                OpenAiTranslationClient.request(job.configs, job.song, job.originalLines) {
                    job.onProgress?.invoke(it)
                }
            if (job.state == TranslationJobState.CANCELLED) return
            if (!apiResults.isNullOrEmpty()) {
                val saved = cache.saveToDb(
                    job.key.contentId, apiResults, job.song, job.key.ticket,
                )
                HookLogger.d(TAG, "翻译任务完成: song=${job.songName}, cached=$saved")
            }
            job.state = TranslationJobState.COMPLETED
            job.deferred.complete(apiResults)
        } catch (e: CancellationException) {
            job.state = TranslationJobState.CANCELLED
            job.deferred.cancel(e)
            throw e
        } catch (e: Exception) {
            job.state = TranslationJobState.COMPLETED
            HookLogger.e(TAG, "翻译任务失败: song=${job.songName}", e)
            job.deferred.complete(null)
        } finally {
            synchronized(lock) {
                if (jobs.remove(job.key, job)) {
                    running = (running - 1).coerceAtLeast(0)
                    dispatchNextLocked()
                }
            }
        }
    }

    private data class TranslationJob(
        val key: AiTranslationJobKey,
        val songName: String,
        val configs: AiTranslationConfigs,
        val song: Song,
        val originalLines: List<String>,
        @Volatile var onProgress: ((AiTranslationProgress) -> Unit)? = null,
        val deferred: CompletableDeferred<List<TranslationItem>?> = CompletableDeferred(),
        var coroutineJob: Job? = null,
        @Volatile
        var state: TranslationJobState = TranslationJobState.PENDING
    )

    private enum class TranslationJobState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED
    }
}


