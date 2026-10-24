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

/**
 * Apple Music-only fallback for NPatch / other single-process module hosts.
 * Calls the separately installed Enhanced app so credentials remain private.
 * Existing SystemUI/Lyricon routing is not changed.
 */
internal class AppleAppAiTranslationClient(
    context: Context,
    private val isSystemUiBridgeActive: () -> Boolean,
    private val onTranslationReceived: (ByteArray) -> Unit,
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

    @Volatile
    private var generation = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = IAppleMusicAiTranslator.Stub.asInterface(service)
            ProviderLogger.info("standalone_ai stage=service_connected")
            dispatchPending()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            ProviderLogger.info("standalone_ai stage=service_disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            binder = null
            if (binding) {
                runCatching { appContext.unbindService(this) }
                binding = false
            }
            bindIfNeeded()
        }

        override fun onNullBinding(name: ComponentName) {
            binder = null
            if (binding) {
                runCatching { appContext.unbindService(this) }
                binding = false
            }
        }
    }

    /** No network work is performed on Apple Music's UI or Binder threads. */
    fun submit(compressedSong: ByteArray) {
        mainHandler.post {
            if (previous?.contentEquals(compressedSong) == true) return@post
            previous = compressedSong.copyOf()
            generation += 1
            pending = compressedSong.takeIf { it.isNotEmpty() }?.copyOf()
            ProviderLogger.info(
                "standalone_ai stage=song_submitted bytes=${compressedSong.size} " +
                    "generation=$generation bridgeActive=${isSystemUiBridgeActive()}"
            )
            if (pending == null || isSystemUiBridgeActive()) {
                pending = null
                // Stop spending API tokens on a song that has ended or was
                // handed back to the regular SystemUI path.
                runCatching { binder?.cancel() }
                return@post
            }
            bindIfNeeded()
            dispatchPending()
        }
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
        pending = null
        ProviderLogger.info(
            "standalone_ai stage=request_dispatched bytes=${songPayload.size} generation=$requestGeneration"
        )

        val callback = object : IAppleMusicTranslationReceiver.Stub() {
            override fun onOnlineTranslationResult(compressedSong: ByteArray) {
                val current = requestGeneration == generation && !isSystemUiBridgeActive()
                ProviderLogger.info(
                    "standalone_ai stage=reply_received bytes=${compressedSong.size} " +
                        "current=$current"
                )
                if (current) {
                    onTranslationReceived(compressedSong)
                }
            }

            override fun onOnlineTranslationCleared(songId: String?) = Unit

            override fun onMissingLyricsSupplementCleared(songId: String?) = Unit

            override fun onMissingLyricsSupplementResult(compressedSong: ByteArray) = Unit

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
        }.onFailure {
            binder = null
            // The service may have died after connection; allow a later song
            // publication to retry instead of silently losing the request.
            previous = null
            ProviderLogger.error(
                "standalone_ai stage=ipc_failed type=${it.javaClass.simpleName}"
            )
        }
    }
}
