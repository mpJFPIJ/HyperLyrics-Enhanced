package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/**
 * In-flight request de-duplication only. Metadata, translations and existing
 * phonetics may change during source races without changing the song's source
 * lyrics. Never log or persist this value.
 */
internal object AppleMusicAiRequestIdentity {
    fun key(song: Song): String = buildString {
        fun part(text: String?) {
            val safe = text.orEmpty()
            append(safe.length).append(':').append(safe)
        }
        part(song.id)
        part(song.name)
        part(song.artist)
        val lines = song.lyrics.orEmpty()
        append(lines.size).append(':')
        lines.forEach { part(it.text) }
    }
}
