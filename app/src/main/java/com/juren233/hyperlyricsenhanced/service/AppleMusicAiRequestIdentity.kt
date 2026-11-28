package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.root.aitrans.AIPronunciationPolicy

/** Decoded service inputs are private snapshots. Equality includes the native line and word clocks. */
internal data class AppleMusicAiDelivery(val song: Song) {
    val semanticKey: String = AppleMusicAiRequestIdentity.key(song)
    val hasMissingTranslation = song.lyrics.orEmpty().any { it.translation.isNullOrBlank() }
    val hasPronunciationWork = AIPronunciationPolicy.shouldEnrich(song)

    fun canShareInference(other: AppleMusicAiDelivery): Boolean =
        semanticKey == other.semanticKey && hasPronunciationWork == other.hasPronunciationWork &&
            (hasPronunciationWork || hasMissingTranslation == other.hasMissingTranslation)
}

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
