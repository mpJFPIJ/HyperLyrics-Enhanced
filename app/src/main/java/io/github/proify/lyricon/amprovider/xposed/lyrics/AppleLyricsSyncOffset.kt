/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.security.MessageDigest

/**
 * Lyrics-only projection of the real playback clock. A positive value delays the
 * lyrics. Neither media seeks nor Apple/native TTML timestamps are changed.
 */
internal object AppleLyricsSyncOffset {
    private const val MAX_OFFSET_MS = 10_000

    fun normalize(offsetMs: Int): Int = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    fun nextOffsetMs(currentMs: Int, deltaMs: Int): Int =
        (normalize(currentMs).toLong() + deltaMs.toLong())
            .coerceIn(-MAX_OFFSET_MS.toLong(), MAX_OFFSET_MS.toLong())
            .toInt()

    /** Keep native Apple karaoke and the playback progress bar on their original clock. */
    fun positionForSurface(playbackPositionMs: Long, offsetMs: Int, isSupplement: Boolean): Long =
        if (isSupplement) lyricPositionMs(playbackPositionMs, offsetMs) else playbackPositionMs

    /** Only offer controls while the module's active-line driver owns the display. */
    fun canAdjustSupplement(
        hasSupplement: Boolean,
        nativeLyricsKnown: Boolean,
        appleNativeSelected: Boolean,
    ): Boolean = hasSupplement && !nativeLyricsKnown && !appleNativeSelected

    fun lyricPositionMs(playbackPositionMs: Long, offsetMs: Int): Long {
        val playback = playbackPositionMs.coerceAtLeast(0L)
        val offset = normalize(offsetMs).toLong()
        return if (offset >= 0L) {
            (playback - offset).coerceAtLeast(0L)
        } else {
            // A negative offset advances lyrics; even a broken clock must not overflow.
            if (playback > Long.MAX_VALUE + offset) Long.MAX_VALUE else playback - offset
        }
    }
}

/** Song-scoped settings independent of the translated/pronunciation cache. */
internal class AppleLyricsSongOffsetStore(private val preferences: Preferences) {
    interface Preferences {
        fun read(key: String): Int
        /** A null value removes the stored override. */
        fun write(key: String, value: Int?)
    }

    fun get(songId: String?): Int = storageKey(songId)
        ?.let { AppleLyricsSyncOffset.normalize(preferences.read(it)) }
        ?: 0

    /** A blank song ID cannot accidentally turn this into a global setting. */
    fun set(songId: String?, offsetMs: Int): Boolean {
        val key = storageKey(songId) ?: return false
        val offset = AppleLyricsSyncOffset.normalize(offsetMs)
        preferences.write(key, offset.takeUnless { it == 0 })
        return true
    }

    private fun storageKey(songId: String?): String? {
        val id = songId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return null
        // Avoid persisting raw song IDs as keys in the host application's preferences.
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return "song_" + digest.joinToString("") { byte ->
            val number = byte.toInt() and 0xff
            HEX[number ushr 4].toString() + HEX[number and 0x0f]
        }
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
