/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Android's legacy AtomicFile backup is a recoverable owner, not a missing song. */
internal object AppleLyricsAtomicBackupPolicy {
    fun readableSource(base: File): File? {
        val backup = File(base.path + ".bak")
        return backup.takeIf(File::exists) ?: base.takeIf(File::exists)
    }
}

/** Private host-side persistence; Context.noBackupFilesDir is excluded from Auto Backup. */
internal class AppleLyricsLineCorrectionFileStorage(application: Application) :
    AppleLyricsLineCorrectionStore.Storage {
    private val directory = File(application.noBackupFilesDir, "hle_user_lyric_corrections")
    private val allowedKey = Regex("song_[0-9a-f]{64}")

    override fun read(key: String): String? {
        val file = target(key)
        // Android AtomicFile may restore its legacy .bak even if the base
        // file is absent. Checking only base.exists() would mistake that
        // recoverable user data for an empty song and overwrite it.
        val readableFile = AppleLyricsAtomicBackupPolicy.readableSource(file) ?: return null
        // Oversized existing data is damaged/unsupported, not a nonexistent
        // song. Returning null would let the next edit overwrite it as empty.
        if (readableFile.length() > MAX_STORED_BYTES) {
            throw IOException("Existing lyric correction exceeds private storage limit")
        }
        val contents = AtomicFile(file).readFully()
        if (contents.size > MAX_STORED_BYTES) {
            throw IOException("Restored lyric correction exceeds private storage limit")
        }
        return contents.toString(Charsets.UTF_8)
    }

    override fun write(key: String, content: String?) {
        val file = target(key)
        val atomic = AtomicFile(file)
        if (content == null) {
            atomic.delete()
            return
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_STORED_BYTES) {
            throw IOException("Lyric correction exceeds allowed private storage size")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not initialize private lyric correction directory")
        }
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun target(key: String): File {
        require(allowedKey.matches(key)) { "Invalid lyric correction storage key" }
        return File(directory, "$key.dat")
    }

    private companion object {
        const val MAX_STORED_BYTES = 2 * 1024 * 1024
    }
}
