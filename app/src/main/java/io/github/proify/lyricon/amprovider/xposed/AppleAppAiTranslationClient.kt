package io.github.proify.lyricon.amprovider.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.juren233.hyperlyricsenhanced.IAppleMusicAiTranslator
import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver

/** A process death may cancel an in-flight Binder request without returning
 * any result. Replay only the same still-current attempt, and let each new
 * attempt revoke a late callback from its disconnected predecessor. */
internal object AppleStandaloneAiRequestPolicy {
    data class ConnectionRecovery(
        val releasePreviousBinding: Boolean,
        val requestNewBinding: Boolean,
    )

    fun connectionRecoveryAfterIpcFailure(
        binding: Boolean, pendingRetry: Boolean, bridgeActive: Boolean,
        consecutiveFailures: Int,
    ): ConnectionRecovery = ConnectionRecovery(
        releasePreviousBinding = binding,
        requestNewBinding = pendingRetry && !bridgeActive && consecutiveFailures == 1,
    )

    fun shouldReplay(
        requestGeneration: Long, currentGeneration: Long,
        requestAttempt: Long, deliveredAttempt: Long,
        bridgeActive: Boolean,
    ): Boolean = !bridgeActive && requestGeneration == currentGeneration &&
        requestAttempt != deliveredAttempt

    fun mayAcceptResponse(
        requestGeneration: Long, currentGeneration: Long,
        requestAttempt: Long, activeAttempt: Long,
        bridgeActive: Boolean,
    ): Boolean = !bridgeActive && requestGeneration == currentGeneration &&
        requestAttempt == activeAttempt
}

/**
 * Apple Music-only fallback for NPatch / other single-process module hosts.
 * Calls the separately installed Enhanced app so credentials remain private.
 * Existing SystemUI/Lyricon routing is not changed.
 */
internal class AppleAppAiTranslationClient(
    context: Context,
    private val isSystemUiBridgeActive: () -> Boolean,
    private val onTranslationReceived: (ByteArray, () -> Boolean) -> Unit,
    private val onTranslationCleared: (String) -> Unit,
) {
    private companion object {
        const val APP_PACKAGE = "com.juren233.hyperlyricsenhanced"
        const val SERVICE_CLASS =
            "com.juren233.hyperlyricsenhanced.service.AppleMusicAiTranslationService"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var binder: IAppleMusicAiTranslator? = null
    private var binding = false
    private var pending: ByteArray? = null
    private var previous: ByteArray? = null
    private data class InFlight(val payload: ByteArray, val generation: Long, val attempt: Long)
    /** Main-thread owned; callbacks publish only the delivered attempt via volatile. */
    private var inFlight: InFlight? = null
    /** Prevent a repeatedly failing one-way service from causing a main-thread bind loop. */
    private var consecutiveIpcFailures = 0
    @Volatile private var activeAttempt = 0L
    @Volatile private var deliveredAttempt = -1L

    // Deleting a song must not cause the still-playing song to be translated
    // again on the next metadata refresh. A new track re-enables translation.
    private var suppressedSongId: String? = null

    @Volatile
    private var generation = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = IAppleMusicAiTranslator.Stub.asInterface(service)
            ProviderLogger.info("standalone_ai stage=service_connected")
            dispatchPending()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recoverInterruptedRequest()
            binder = null
            ProviderLogger.info("standalone_ai stage=service_disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            recoverInterruptedRequest()
            binder = null
            if (binding) {
                runCatching { appContext.unbindService(this) }
                binding = false
            }
            bindIfNeeded()
        }

        override fun onNullBinding(name: ComponentName) {
            recoverInterruptedRequest()
            binder = null
            if (binding) {
                runCatching { appContext.unbindService(this) }
                binding = false
            }
            // No automatic connection can complete this attempt. A future
            // same-song publication is allowed to try the service again.
            previous = null
        }
    }

    private fun recoverInterruptedRequest() {
        val outstanding = inFlight ?: return
        if (AppleStandaloneAiRequestPolicy.shouldReplay(
                requestGeneration = outstanding.generation,
                currentGeneration = generation,
                requestAttempt = outstanding.attempt,
                deliveredAttempt = deliveredAttempt,
                bridgeActive = isSystemUiBridgeActive(),
            )
        ) {
            pending = outstanding.payload.copyOf()
            ProviderLogger.info(
                "standalone_ai stage=request_requeued generation=${outstanding.generation}"
            )
        }
        inFlight = null
    }

    /** No network work is performed on Apple Music's UI or Binder threads. */
    fun submit(compressedSong: ByteArray, songId: String?) {
        mainHandler.post {
            if (songId != null && songId == suppressedSongId) {
                ProviderLogger.info("standalone_ai stage=song_suppressed_after_clear")
                return@post
            }
            if (songId != suppressedSongId) suppressedSongId = null
            if (previous?.contentEquals(compressedSong) == true) return@post
            previous = compressedSong.copyOf()
            generation += 1
            consecutiveIpcFailures = 0
            inFlight = null
            AppleAiProgressOverlay.update("preparing")
            pending = compressedSong.takeIf { it.isNotEmpty() }?.copyOf()
            ProviderLogger.info(
                "standalone_ai stage=song_submitted bytes=${compressedSong.size} " +
                    "generation=$generation bridgeActive=${isSystemUiBridgeActive()}"
            )
            if (pending == null || isSystemUiBridgeActive()) {
                pending = null
                AppleAiProgressOverlay.update("cancelled")
                // Stop spending API tokens on a song that has ended or was
                // handed back to the regular SystemUI path.
                runCatching { binder?.cancel() }
                return@post
            }
            bindIfNeeded()
            dispatchPending()
        }
    }

    /**
     * A confirmed, song-scoped deletion. Do not request a new translation
     * until the next playback publication; stale in-flight replies are ignored.
     */
    fun clearSong(compressedSong: ByteArray, expectedSongId: String): Boolean {
        val target = binder ?: return false
        if (isSystemUiBridgeActive() || compressedSong.isEmpty()) return false
        generation += 1
        val currentGeneration = generation
        pending = null
        inFlight = null
        previous = null
        suppressedSongId = expectedSongId
        val callback = object : IAppleMusicTranslationReceiver.Stub() {
            override fun onOnlineTranslationResult(compressedSong: ByteArray) = Unit

            override fun onAiTranslationProgress(
                stage: String?, batch: Int, totalBatches: Int, fragments: Int,
            ) = Unit

            override fun onOnlineTranslationCleared(songId: String?) {
                if (songId == expectedSongId && generation == currentGeneration) {
                    onTranslationCleared(expectedSongId)
                }
            }

            override fun onMissingLyricsSupplementCleared(songId: String?) = Unit
            override fun onMissingLyricsSupplementResult(compressedSong: ByteArray) = Unit
            override fun onOnlineTranslationResultForVisit(compressedSong: ByteArray, visitId: Long) = Unit
            override fun onOnlineTranslationClearedForVisit(songId: String?, visitId: Long) = Unit
            override fun onMissingLyricsSupplementResultForVisit(compressedSong: ByteArray, visitId: Long) = Unit
            override fun onMissingLyricsSupplementClearedForVisit(songId: String?, visitId: Long) = Unit
            override fun onOnlineTranslationSourceSwitchResult(
                requestId: Long,
                songId: String?,
                contentType: String?,
                requestedSource: String?,
                actualSource: String?,
                successful: Boolean,
            ) = Unit
        }
        return runCatching {
            target.clearSongTranslation(compressedSong.copyOf(), callback)
            AppleAiProgressOverlay.update("cancelled")
            ProviderLogger.info("standalone_ai stage=song_clear_requested")
            true
        }.onFailure {
            suppressedSongId = null
            ProviderLogger.error("standalone_ai stage=song_clear_ipc_failed", it)
        }.getOrDefault(false)
    }

    private fun bindIfNeeded() {
        if (pending == null || binding || isSystemUiBridgeActive()) return
        val intent = Intent().setClassName(APP_PACKAGE, SERVICE_CLASS)
        binding = runCatching {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            ProviderLogger.error("standalone_ai stage=bind_exception type=${it.javaClass.simpleName}")
        }.getOrDefault(false)
        ProviderLogger.info("standalone_ai stage=bind_requested success=$binding")
        if (!binding) {
            // The separately installed app may not be present yet. Do not
            // permanently deduplicate this song if connecting was impossible.
            previous = null
            AppleAiProgressOverlay.update("failed")
            ProviderLogger.info("Apple Music standalone AI service unavailable")
        }
    }

    private fun dispatchPending() {
        if (isSystemUiBridgeActive()) {
            pending = null
            return
        }
        val target = binder ?: return
        val songPayload = pending ?: return
        val requestGeneration = generation
        val requestAttempt = ++activeAttempt
        inFlight = InFlight(songPayload.copyOf(), requestGeneration, requestAttempt)
        pending = null
        ProviderLogger.info(
            "standalone_ai stage=request_dispatched bytes=${songPayload.size} generation=$requestGeneration"
        )

        val callback = object : IAppleMusicTranslationReceiver.Stub() {
            override fun onOnlineTranslationResult(compressedSong: ByteArray) {
                val current = AppleStandaloneAiRequestPolicy.mayAcceptResponse(
                    requestGeneration, generation, requestAttempt, activeAttempt,
                    isSystemUiBridgeActive(),
                )
                ProviderLogger.info(
                    "standalone_ai stage=reply_received bytes=${compressedSong.size} " +
                        "current=$current"
                )
                if (current) {
                    deliveredAttempt = requestAttempt
                    mainHandler.post {
                        if (inFlight?.attempt == requestAttempt) inFlight = null
                        // A disconnect can already have requeued this same
                        // request. Its successful reply makes that replay moot.
                        if (generation == requestGeneration &&
                            activeAttempt == requestAttempt
                        ) pending = null
                    }
                    onTranslationReceived(compressedSong) {
                        AppleStandaloneAiRequestPolicy.mayAcceptResponse(
                            requestGeneration, generation, requestAttempt, activeAttempt,
                            isSystemUiBridgeActive(),
                        )
                    }
                    // The app process sends a final per-line completeness stage
                    // immediately after this callback; translation receipt alone
                    // cannot claim every Japanese reading has been generated.
                    AppleAiProgressOverlay.update("result_received")
                }
            }

            override fun onAiTranslationProgress(
                stage: String?, batch: Int, totalBatches: Int, fragments: Int,
            ) {
                if (AppleStandaloneAiRequestPolicy.mayAcceptResponse(
                        requestGeneration, generation, requestAttempt, activeAttempt,
                        isSystemUiBridgeActive(),
                    )
                ) {
                    AppleAiProgressOverlay.update(stage ?: return, batch, totalBatches, fragments)
                }
            }

            override fun onOnlineTranslationCleared(songId: String?) = Unit

            override fun onMissingLyricsSupplementCleared(songId: String?) = Unit

            override fun onMissingLyricsSupplementResult(compressedSong: ByteArray) = Unit
            override fun onOnlineTranslationResultForVisit(compressedSong: ByteArray, visitId: Long) = Unit
            override fun onOnlineTranslationClearedForVisit(songId: String?, visitId: Long) = Unit
            override fun onMissingLyricsSupplementResultForVisit(compressedSong: ByteArray, visitId: Long) = Unit
            override fun onMissingLyricsSupplementClearedForVisit(songId: String?, visitId: Long) = Unit

            override fun onOnlineTranslationSourceSwitchResult(
                requestId: Long,
                songId: String?,
                contentType: String?,
                requestedSource: String?,
                actualSource: String?,
                successful: Boolean,
            ) = Unit
        }
        runCatching {
            target.translate(songPayload, callback)
        }.onSuccess {
            consecutiveIpcFailures = 0
        }.onFailure {
            consecutiveIpcFailures++
            binder = null
            // A synchronous Binder exception does not guarantee Android will
            // deliver onServiceDisconnected(). Keep this payload for one new
            // connection instead of leaving binder=null with binding=true.
            if (inFlight?.attempt == requestAttempt) {
                pending = songPayload.copyOf()
                inFlight = null
            }
            previous = null
            val recovery = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
                binding = binding, pendingRetry = pending != null,
                bridgeActive = isSystemUiBridgeActive(),
                consecutiveFailures = consecutiveIpcFailures,
            )
            if (recovery.releasePreviousBinding) {
                runCatching { appContext.unbindService(serviceConnection) }
            }
            binding = false
            if (recovery.requestNewBinding) bindIfNeeded()
            else if (pending != null) ProviderLogger.info(
                "standalone_ai stage=ipc_reconnect_paused failures=$consecutiveIpcFailures"
            )
            AppleAiProgressOverlay.update("failed")
            ProviderLogger.error(
                "standalone_ai stage=ipc_failed type=${it.javaClass.simpleName}"
            )
        }
    }
}
