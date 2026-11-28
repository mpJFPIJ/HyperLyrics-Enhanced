/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** Reuse established flat cache names when safe; never interpret an incoming
 * song ID as a relative/absolute filesystem path or an overlong filename. */
internal object AppleSongCacheFilePolicy {
    private val hashedNamePrefix = Regex("id_[0-9a-f]{64}")

    fun fileName(id: String): String {
        val direct = "$id.json.gz"
        val safe = id.isNotBlank() && id != "." && id != ".." &&
            !hashedNamePrefix.matches(id) &&
            id.none { it == '/' || it == '\\' || Character.isISOControl(it) } &&
            direct.toByteArray(Charsets.UTF_8).size <= 240
        if (safe) return direct
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return "id_" + bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            HEX[value ushr 4].toString() + HEX[value and 0x0f]
        } + ".json.gz"
    }

    private const val HEX = "0123456789abcdef"
}

/** Publish a fully written cache file in one filesystem operation. A failed
 * compression/write/move must leave the prior cache record intact. */
internal object AppleSongCacheAtomicFile {
    fun replace(target: File, contents: ByteArray) {
        replaceWith(target) { it.write(contents) }
    }

    fun replaceWith(target: File, writeContents: (OutputStream) -> Unit) {
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Song cache target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not initialize song cache directory")
        }
        // Staging beside the destination keeps the rename on one filesystem.
        // Random names avoid writers in separate processes sharing a temp file.
        val staged = File.createTempFile(".song-cache-", ".pending", parent)
        try {
            FileOutputStream(staged).use { stream ->
                writeContents(stream)
            }
            // This reconstructible cache is sometimes saved on the player
            // thread; forced flash fsync on every update would add UI latency.
            // The closed staged file is published only after all bytes arrive.
            // Do not fall back to a non-atomic copy/truncation on a provider
            // that cannot guarantee an atomic replacement.
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staged.delete()
        }
    }
}

object DiskSongManager {
    /**
     * 最近解码过的歌词缓存。切歌后 App 内元数据每覆盖一次都会触发一次目录重发布，
     * 221012 实测同一首歌在主线程被重复读盘解码 15–30 次（每次 0.5–6 ms）。
     * 文件只由本模块写入，[save]/[delete] 时失效；返回浅拷贝，调用方只改顶层元数据字段。
     */
    private const val DECODED_SONG_CACHE_SIZE = 8
    private val decodedSongs = object : LinkedHashMap<String, AppleSong>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppleSong>?): Boolean =
            size > DECODED_SONG_CACHE_SIZE
    }

    private val generationCounter = java.util.concurrent.atomic.AtomicLong()

    /** 歌曲缓存每次保存或删除后递增，供上层判断缓存内容是否可能已变。 */
    val generation: Long get() = generationCounter.get()

    private var baseDir: File? = null
    private var missingLyricsBaseDir: File? = null

    fun initialize(context: Context) {
        val lyriconDir = File(context.filesDir, "lyricon")

        val locale = Locale.getDefault()
        baseDir = File(File(lyriconDir, "songs"), locale.toLanguageTag())
        baseDir?.mkdirs()
        missingLyricsBaseDir = File(
            File(lyriconDir, "missing_lyrics"),
            locale.toLanguageTag(),
        )
        missingLyricsBaseDir?.mkdirs()
    }

    private val writeLock = Any()

    /**
     * 写盘后把刚保存的内容直接放进解码缓存：221027 实测保存后紧接着的 `load` 会重新读盘解码
     * （每次 5–35 ms，同一首歌 2–3 次，部分在主线程）。写入串行化，缓存更新与 [generation]
     * 递增在同一把锁内完成，[load] 只在读盘期间 generation 未变时才回填，避免把旧文件放回缓存。
     */
    fun save(appleSong: AppleSong): Boolean {
        val id = appleSong.adamId
        if (id.isNullOrBlank()) return false
        val string = json.encodeToString(appleSong)
        synchronized(writeLock) {
            synchronized(decodedSongs) {
                decodedSongs.remove(id)
                generationCounter.incrementAndGet()
            }
            val written = runCatching {
                AppleSongCacheAtomicFile.replace(
                    getFile(id),
                    string.toByteArray(Charsets.UTF_8).deflate(),
                )
            }.isSuccess
            synchronized(decodedSongs) {
                generationCounter.incrementAndGet()
                if (written) decodedSongs[id] = appleSong.copy() else decodedSongs.remove(id)
            }
            return written
        }
    }

    /**
     * 内容与缓存完全相同时跳过写盘：Apple 对同一首歌会反复构建歌词模型，原先每次都在主线程
     * 重新编码、压缩并写盘，并递增 [generation] 迫使目录重发布重新计算。
     */
    fun saveIfChanged(appleSong: AppleSong): Boolean {
        val id = appleSong.adamId
        if (id.isNullOrBlank()) return false
        if (load(id) == appleSong) return true
        return save(appleSong)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun load(id: String): AppleSong? {
        val readGeneration = synchronized(decodedSongs) {
            decodedSongs[id]?.let { return it.copy() }
            generationCounter.get()
        }
        val decoded = runCatching {
            readCompressedJson("song", id, getFile(id)) { text ->
                json.decodeFromString<AppleSong>(text)
            }
        }.getOrNull() ?: return null
        synchronized(decodedSongs) {
            if (generationCounter.get() == readGeneration) decodedSongs[id] = decoded
        }
        return decoded.copy()
    }

    fun delete(id: String): Boolean = synchronized(writeLock) {
        synchronized(decodedSongs) {
            decodedSongs.remove(id)
            generationCounter.incrementAndGet()
        }
        runCatching {
            val file = getFile(id)
            !file.exists() || file.delete()
        }.getOrDefault(false)
    }

    fun saveMissingLyrics(song: Song): Boolean {
        val id = song.id?.takeIf(String::isNotBlank) ?: return false
        val string = json.encodeToString(song)
        return synchronized(writeLock) {
            runCatching {
                AppleSongCacheAtomicFile.replace(
                    getMissingLyricsFile(id),
                    string.toByteArray(Charsets.UTF_8).deflate(),
                )
            }.isSuccess
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadMissingLyrics(id: String): Song? = runCatching {
        readCompressedJson("missing_lyrics", id, getMissingLyricsFile(id)) { text ->
            json.decodeFromString<Song>(text)
        }
    }.getOrNull()

    /**
     * 读取并解压缓存文件后交给 [decode]。Debug 版记录各阶段耗时与线程，
     * 用于核实切歌时主线程读盘的实际开销（审计第 15 项）。
     */
    private inline fun <T> readCompressedJson(
        kind: String,
        id: String,
        file: File,
        decode: (String) -> T,
    ): T? {
        if (!BuildConfig.DEBUG) {
            return file.takeIf(File::exists)?.readBytes()?.inflate()?.let {
                decode(it.toString(Charsets.UTF_8))
            }
        }
        val startNs = System.nanoTime()
        val bytes = file.takeIf(File::exists)?.readBytes()
        val readNs = System.nanoTime()
        if (bytes == null) {
            ProviderLogger.diagnostic(
                "[SongCacheIo] kind=$kind, id=$id, hit=false, " +
                    "statMs=${(readNs - startNs) / 1_000_000.0}, thread=${Thread.currentThread().name}"
            )
            return null
        }
        val text = bytes.inflate().toString(Charsets.UTF_8)
        val inflateNs = System.nanoTime()
        val decoded = decode(text)
        val decodeNs = System.nanoTime()
        ProviderLogger.diagnostic(
            "[SongCacheIo] kind=$kind, id=$id, hit=true, bytes=${bytes.size}, chars=${text.length}, " +
                "readMs=${(readNs - startNs) / 1_000_000.0}, " +
                "inflateMs=${(inflateNs - readNs) / 1_000_000.0}, " +
                "decodeMs=${(decodeNs - inflateNs) / 1_000_000.0}, " +
                "thread=${Thread.currentThread().name}"
        )
        return decoded
    }

    fun deleteMissingLyrics(id: String): Boolean = synchronized(writeLock) {
        runCatching {
            val file = getMissingLyricsFile(id)
            !file.exists() || file.delete()
        }.getOrDefault(false)
    }

    //fun hasCache(id: String): Boolean = getFile(id).exists()
    private fun getFile(id: String): File = File(baseDir, AppleSongCacheFilePolicy.fileName(id))

    private fun getMissingLyricsFile(id: String): File =
        File(missingLyricsBaseDir, AppleSongCacheFilePolicy.fileName(id))
}
