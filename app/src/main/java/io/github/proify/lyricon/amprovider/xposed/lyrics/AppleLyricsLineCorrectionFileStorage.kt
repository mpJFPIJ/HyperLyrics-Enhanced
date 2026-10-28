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

/** Private host-side persistence; Context.noBackupFilesDir is excluded from Auto Backup. */
internal class AppleLyricsLineCorrectionFileStorage(application: Application) :
    AppleLyricsLineCorrectionStore.Storage {
    private val directory = File(application.noBackupFilesDir, "hle_user_lyric_corrections")
    private val allowedKey = Regex("song_[0-9a-f]{64}")

    override fun read(key: String): String? {
        val file = target(key)
        if (!file.exists() || file.length() > MAX_STORED_BYTES) return null
        return AtomicFile(file).readFully().toString(Charsets.UTF_8)
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
