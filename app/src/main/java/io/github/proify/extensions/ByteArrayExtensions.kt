/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Higher than any normal song payload; bounds decompression of untrusted IPC bytes. */
private const val MAX_INFLATED_LYRIC_BYTES = 16 * 1024 * 1024

/**
 * ZLIB压缩字节数组
 */
fun ByteArray.deflate(): ByteArray {
    if (isEmpty()) return byteArrayOf()

    return Deflater().run {
        setInput(this@deflate)
        finish()

        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (!finished()) {
                output.write(buffer, 0, deflate(buffer))
            }
            output.toByteArray()
        }.also { end() }
    }
}

/**
 * ZLIB解压字节数组
 */
fun ByteArray.inflate(): ByteArray {
    if (isEmpty()) return byteArrayOf()

    val inflater = Inflater().apply {
        setInput(this@inflate)
    }
    return try {
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.finished()) break
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw DataFormatException("Incomplete or dictionary-compressed lyrics")
                    }
                    throw DataFormatException("ZLIB decompressor made no progress")
                }
                if (output.size() > MAX_INFLATED_LYRIC_BYTES - count) {
                    throw IllegalArgumentException("Decompressed lyric payload is too large")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally {
        inflater.end()
    }
}
