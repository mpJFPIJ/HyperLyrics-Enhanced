/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import java.security.MessageDigest
import java.util.Base64

/**
 * User-authored line corrections live outside source lyrics, native TTML, AI results and caches.
 * The caller supplies a private, no-backup storage backend; no song ID is used as a filename.
 */
internal class AppleLyricsLineCorrectionStore(private val storage: Storage) {
    interface Storage {
        fun read(key: String): String?
        /** A null payload removes all corrections for the specified song. */
        fun write(key: String, content: String?)
    }

    enum class Field { ORIGINAL, TRANSLATION, PRONUNCIATION }

    data class Correction(
        val original: String? = null,
        val translation: String? = null,
        val pronunciation: String? = null,
    ) {
        fun value(field: Field): String? = when (field) {
            Field.ORIGINAL -> original
            Field.TRANSLATION -> translation
            Field.PRONUNCIATION -> pronunciation
        }

        fun with(field: Field, content: String?): Correction = when (field) {
            Field.ORIGINAL -> copy(original = content)
            Field.TRANSLATION -> copy(translation = content)
            Field.PRONUNCIATION -> copy(pronunciation = content)
        }

        fun isEmpty(): Boolean = original == null && translation == null && pronunciation == null
    }

    private data class Row(val begin: Long, val end: Long, val sourceText: String)

    private val whitespace = Regex("\\s+")
    private var loadedSongId: String? = null
    private var loadedStorageKey: String? = null
    private var loadedRows = emptyMap<Row, Correction>()

    @Synchronized
    fun correction(songId: String?, begin: Long, end: Long, sourceText: String?): Correction? {
        val id = validSongId(songId) ?: return null
        val row = validRow(begin, end, sourceText) ?: return null
        if (!load(id)) return null
        return loadedRows[row]
    }

    @Synchronized
    fun hasAny(songId: String?, field: Field): Boolean {
        val id = validSongId(songId) ?: return false
        if (!load(id)) return false
        return loadedRows.values.any { it.value(field) != null }
    }

    /** A blank value resets only this user-edit field, leaving online/AI text intact. */
    @Synchronized
    fun update(
        songId: String?,
        begin: Long,
        end: Long,
        sourceText: String?,
        field: Field,
        value: String?,
    ): Boolean {
        val id = validSongId(songId) ?: return false
        val row = validRow(begin, end, sourceText) ?: return false
        val sanitized = value?.trim()?.takeIf(String::isNotEmpty)
        if (sanitized != null && sanitized.length > MAX_TEXT_LENGTH) return false
        if (field == Field.PRONUNCIATION && sanitized != null &&
            RomanizationPolicy.sanitizeSupplement(row.sourceText, sanitized) == null
        ) return false
        if (!load(id)) return false
        val before = loadedRows[row] ?: Correction()
        val after = before.with(field, sanitized)
        if (before == after) return false
        val next = loadedRows.toMutableMap()
        if (after.isEmpty()) next.remove(row) else next[row] = after
        if (next.size > MAX_LINES_PER_SONG) return false
        val encoded = encode(next)
        if (encoded != null && encoded.length > MAX_SONG_BYTES) return false
        val key = loadedStorageKey ?: return false
        if (runCatching { storage.write(key, encoded) }.isFailure) return false
        loadedRows = next
        return true
    }

    /** One editor action writes both fields together; invalid input cannot half-save a row. */
    @Synchronized
    fun replaceTranslationAndPronunciation(
        songId: String?,
        begin: Long,
        end: Long,
        sourceText: String?,
        translation: String?,
        pronunciation: String?,
    ): Boolean {
        val id = validSongId(songId) ?: return false
        val row = validRow(begin, end, sourceText) ?: return false
        val newTranslation = translation?.trim()?.takeIf(String::isNotEmpty)
        val newPronunciation = pronunciation?.trim()?.takeIf(String::isNotEmpty)
        if (newTranslation != null && newTranslation.length > MAX_TEXT_LENGTH) return false
        if (newPronunciation != null && (newPronunciation.length > MAX_TEXT_LENGTH ||
                RomanizationPolicy.sanitizeSupplement(row.sourceText, newPronunciation) == null)
        ) return false
        if (!load(id)) return false
        val before = loadedRows[row] ?: Correction()
        val after = before.copy(translation = newTranslation, pronunciation = newPronunciation)
        if (before == after) return true
        val next = loadedRows.toMutableMap()
        if (after.isEmpty()) next.remove(row) else next[row] = after
        if (next.size > MAX_LINES_PER_SONG) return false
        val encoded = encode(next)
        if (encoded != null && encoded.length > MAX_SONG_BYTES) return false
        val key = loadedStorageKey ?: return false
        if (runCatching { storage.write(key, encoded) }.isFailure) return false
        loadedRows = next
        return true
    }

    private fun load(songId: String): Boolean {
        if (loadedSongId == songId) return true
        val key = storageKey(songId)
        val decoded = runCatching { decode(storage.read(key)) }
        if (decoded.isFailure) {
            loadedSongId = null
            loadedStorageKey = null
            loadedRows = emptyMap()
            return false
        }
        loadedSongId = songId
        loadedStorageKey = key
        loadedRows = decoded.getOrThrow()
        return true
    }

    private fun validSongId(songId: String?): String? =
        songId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }

    private fun validRow(begin: Long, end: Long, sourceText: String?): Row? {
        if (begin < 0L || end < begin) return null
        val normalized = sourceText?.replace(whitespace, " ")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH }
            ?: return null
        return Row(begin, end, normalized)
    }

    private fun storageKey(songId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(songId.toByteArray(Charsets.UTF_8))
        return "song_" + bytes.joinToString("") { byte ->
            val unsigned = byte.toInt() and 0xff
            HEX[unsigned ushr 4].toString() + HEX[unsigned and 15]
        }
    }

    private fun encode(rows: Map<Row, Correction>): String? {
        if (rows.isEmpty()) return null
        return buildString {
            append("v1\n")
            rows.toSortedMap(compareBy<Row> { it.begin }.thenBy { it.end }.thenBy { it.sourceText })
                .forEach { (row, correction) ->
                    append(row.begin).append('\t').append(row.end).append('\t')
                    append(base64(row.sourceText)).append('\t')
                    append(base64(correction.original)).append('\t')
                    append(base64(correction.translation)).append('\t')
                    append(base64(correction.pronunciation)).append('\n')
                }
        }
    }

    private fun decode(raw: String?): Map<Row, Correction> {
        if (raw == null || raw.length > MAX_SONG_BYTES || !raw.startsWith("v1\n")) return emptyMap()
        val entries = mutableMapOf<Row, Correction>()
        raw.lineSequence().drop(1).take(MAX_LINES_PER_SONG).forEach { encoded ->
            val parts = encoded.split('\t')
            if (parts.size != 6) return@forEach
            val begin = parts[0].toLongOrNull() ?: return@forEach
            val end = parts[1].toLongOrNull() ?: return@forEach
            val source = unbase64(parts[2]) ?: return@forEach
            val row = validRow(begin, end, source) ?: return@forEach
            val correction = Correction(
                original = unbase64(parts[3]),
                translation = unbase64(parts[4]),
                pronunciation = unbase64(parts[5]),
            )
            if (!correction.isEmpty() && listOfNotNull(
                    correction.original, correction.translation, correction.pronunciation,
                ).all { it.length <= MAX_TEXT_LENGTH }
            ) entries[row] = correction
        }
        return entries
    }

    private fun base64(value: String?): String = value?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    } ?: "-"

    private fun unbase64(value: String): String? = if (value == "-") {
        null
    } else {
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 1024
        const val MAX_LINES_PER_SONG = 256
        const val MAX_SONG_BYTES = 2 * 1024 * 1024
        const val HEX = "0123456789abcdef"
    }
}
