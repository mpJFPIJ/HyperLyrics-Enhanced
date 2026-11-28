/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class AppleLyricsLineCorrectionStoreTest {
    @Test fun `legacy AtomicFile backup is readable even without the base file`() {
        val directory = Files.createTempDirectory("hle-correction-backup-test").toFile()
        val base = File(directory, "song_test.dat")
        val backup = File(base.path + ".bak")
        try {
            assertNull(AppleLyricsAtomicBackupPolicy.readableSource(base))
            base.writeText("base")
            assertEquals(base, AppleLyricsAtomicBackupPolicy.readableSource(base))
            backup.writeText("recoverable")
            assertEquals(backup, AppleLyricsAtomicBackupPolicy.readableSource(base))
            assertTrue(base.delete())
            assertEquals(backup, AppleLyricsAtomicBackupPolicy.readableSource(base))
        } finally {
            backup.delete()
            base.delete()
            directory.delete()
        }
    }

    private class MemoryStorage : AppleLyricsLineCorrectionStore.Storage {
        val files = mutableMapOf<String, String>()
        var failNextRead = false
        var readCount = 0

        override fun read(key: String): String? {
            readCount++
            if (failNextRead) {
                failNextRead = false
                throw IllegalStateException("temporary storage read failure")
            }
            return files[key]
        }

        override fun write(key: String, content: String?) {
            if (content == null) files.remove(key) else files[key] = content
        }
    }

    @Test
    fun `persists field-specific user corrections without changing the source line`() {
        val storage = MemoryStorage()
        val first = AppleLyricsLineCorrectionStore(storage)
        assertTrue(first.update("song-1", 1000, 2000, "君の  名は", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "너의 이름은"))
        assertTrue(first.update("song-1", 1000, 2000, "君の 名は", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION, "키미노 나와"))
        assertFalse(first.update("song-1", 1000, 2000, "君の 名は", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION, "키미노 나와"))

        val reopened = AppleLyricsLineCorrectionStore(storage)
        val correction = reopened.correction("song-1", 1000, 2000, "君の 名は")
        assertEquals("너의 이름은", correction?.translation)
        assertEquals("키미노 나와", correction?.pronunciation)
        assertNull(correction?.original)
        assertTrue(reopened.hasAny("song-1", AppleLyricsLineCorrectionStore.Field.TRANSLATION))
        assertTrue(reopened.hasAny("song-1", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION))
        assertNull(reopened.correction("song-2", 1000, 2000, "君の 名は"))
        assertNull(reopened.correction("song-1", 1001, 2000, "君の 名は"))
        assertNull(reopened.correction("song-1", 1000, 2000, "別の行"))
        assertEquals(1, storage.files.size)
        assertFalse(storage.files.keys.single().contains("song-1"))
    }

    @Test
    fun `clearing one field keeps other corrections and never overwrites online source`() {
        val storage = MemoryStorage()
        val corrections = AppleLyricsLineCorrectionStore(storage)
        assertTrue(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.ORIGINAL, "edited line"))
        assertTrue(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "번역"))
        assertTrue(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, null))
        assertEquals("edited line", corrections.correction("s", 10, 20, "line")?.original)
        assertFalse(corrections.hasAny("s", AppleLyricsLineCorrectionStore.Field.TRANSLATION))
        assertTrue(corrections.hasAny("s", AppleLyricsLineCorrectionStore.Field.ORIGINAL))
        assertTrue(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.ORIGINAL, null))
        assertNull(corrections.correction("s", 10, 20, "line"))
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `rejects invalid identities and oversized edits without writing data`() {
        val storage = MemoryStorage()
        val corrections = AppleLyricsLineCorrectionStore(storage)
        assertFalse(corrections.update(" ", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "a"))
        assertFalse(corrections.update("s", -1, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "a"))
        assertFalse(corrections.update("s", 30, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "a"))
        assertFalse(corrections.update("s", 10, 20, " ", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "a"))
        assertFalse(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "x".repeat(1025)))
        assertFalse(corrections.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION, "line"))
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `ignores damaged serialized entries without leaking them into other songs`() {
        val storage = MemoryStorage()
        val store = AppleLyricsLineCorrectionStore(storage)
        assertTrue(store.update("s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "valid"))
        val key = storage.files.keys.single()
        storage.files[key] = "v1\nnot-a-correction\n"
        assertNull(AppleLyricsLineCorrectionStore(storage).correction("s", 10, 20, "line"))
        assertFalse(AppleLyricsLineCorrectionStore(storage).hasAny("s", AppleLyricsLineCorrectionStore.Field.TRANSLATION))
        // A damaged row is not a blank cache: overwriting it could destroy
        // other recoverable user-authored corrections stored in this file.
        val damaged = storage.files[key]
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 11, 21, "second", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(damaged, storage.files[key])
    }

    @Test fun `partially damaged serialized corrections are never silently truncated on save`() {
        val storage = MemoryStorage()
        assertTrue(AppleLyricsLineCorrectionStore(storage).update(
            "s", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "saved",
        ))
        val key = storage.files.keys.single()
        val valid = storage.files[key]!!
        val corrupt = valid + "not-a-valid-row\n"
        storage.files[key] = corrupt
        val reopened = AppleLyricsLineCorrectionStore(storage)
        assertNull(reopened.correction("s", 10, 20, "first"))
        assertFalse(reopened.update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(corrupt, storage.files[key])
    }

    @Test fun `empty serialized record after a header is not silently treated as empty data`() {
        val storage = MemoryStorage()
        assertTrue(AppleLyricsLineCorrectionStore(storage).update(
            "s", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "saved",
        ))
        val key = storage.files.keys.single()
        storage.files[key] = "v1\n\n"
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals("v1\n\n", storage.files[key])
    }

    @Test fun `duplicate or invalid encoded correction fields cannot erase saved records`() {
        val storage = MemoryStorage()
        assertTrue(AppleLyricsLineCorrectionStore(storage).update(
            "s", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "saved",
        ))
        val key = storage.files.keys.single()
        val valid = storage.files[key]!!
        val duplicate = valid + valid.removePrefix("v1\n")
        storage.files[key] = duplicate
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(duplicate, storage.files[key])

        val fields = valid.lineSequence().drop(1).first().split('\t').toMutableList()
        fields[4] = "!invalid-base64!"
        val malformedField = "v1\n" + fields.joinToString("\t") + "\n"
        storage.files[key] = malformedField
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(malformedField, storage.files[key])

        // _w is valid URL-safe base64 for byte FF, but not valid UTF-8 text.
        // Replacing that malformed byte with U+FFFD would mutate user data.
        fields[4] = "_w"
        val invalidUtf8 = "v1\n" + fields.joinToString("\t") + "\n"
        storage.files[key] = invalidUtf8
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(invalidUtf8, storage.files[key])
    }

    @Test fun `noncanonical blank fields and unnormalized source cannot be silently rewritten`() {
        val storage = MemoryStorage()
        assertTrue(AppleLyricsLineCorrectionStore(storage).update(
            "s", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "saved",
        ))
        val key = storage.files.keys.single()
        val validFields = storage.files[key]!!.lineSequence().drop(1).first()
            .split('\t').toMutableList()
        val badBlank = validFields.toMutableList().apply { this[4] = "" }
        val badBlankFile = "v1\n" + badBlank.joinToString("\t") + "\n"
        storage.files[key] = badBlankFile
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(badBlankFile, storage.files[key])

        val noncanonicalSource = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "  first  ".toByteArray(Charsets.UTF_8),
        )
        val badSource = validFields.toMutableList().apply { this[2] = noncanonicalSource }
        val badSourceFile = "v1\n" + badSource.joinToString("\t") + "\n"
        storage.files[key] = badSourceFile
        assertFalse(AppleLyricsLineCorrectionStore(storage).update(
            "s", 30, 40, "other", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "new",
        ))
        assertEquals(badSourceFile, storage.files[key])
    }

    @Test
    fun `failed disk read never overwrites an existing saved edit`() {
        val storage = MemoryStorage()
        assertTrue(AppleLyricsLineCorrectionStore(storage).update(
            "s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "saved",
        ))
        storage.failNextRead = true
        val reopened = AppleLyricsLineCorrectionStore(storage)
        assertFalse(reopened.update(
            "s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION, "라인",
        ))
        assertEquals("saved", reopened.correction("s", 10, 20, "line")?.translation)
        assertTrue(reopened.update(
            "s", 10, 20, "line", AppleLyricsLineCorrectionStore.Field.PRONUNCIATION, "라인",
        ))
        assertEquals("saved", AppleLyricsLineCorrectionStore(storage)
            .correction("s", 10, 20, "line")?.translation)
    }

    @Test
    fun `two field editor saves atomically and rejects invalid pronunciation`() {
        val storage = MemoryStorage()
        val corrections = AppleLyricsLineCorrectionStore(storage)
        assertTrue(corrections.replaceTranslationAndPronunciation(
            "song", 10, 20, "Hello", "안녕", "헬로",
        ))
        val initial = corrections.correction("song", 10, 20, "Hello")
        assertEquals("안녕", initial?.translation)
        assertEquals("헬로", initial?.pronunciation)
        assertFalse(corrections.replaceTranslationAndPronunciation(
            "song", 10, 20, "Hello", "새 번역", "Hello",
        ))
        assertEquals(initial, AppleLyricsLineCorrectionStore(storage)
            .correction("song", 10, 20, "Hello"))
        assertTrue(corrections.replaceTranslationAndPronunciation(
            "song", 10, 20, "Hello", null, null,
        ))
        assertNull(corrections.correction("song", 10, 20, "Hello"))
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `editor rechecks persisted corrections before saving another row in the same song`() {
        val storage = MemoryStorage()
        val firstOwner = AppleLyricsLineCorrectionStore(storage)
        val secondOwner = AppleLyricsLineCorrectionStore(storage)
        assertTrue(firstOwner.update(
            "same", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "하나",
        ))
        assertTrue(secondOwner.update(
            "same", 30, 40, "second", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "둘",
        ))
        // The first owner still has a valid in-memory read cache, but a
        // second process/controller persisted a distinct row since its read.
        assertTrue(firstOwner.replaceTranslationAndPronunciation(
            "same", 50, 60, "third", "셋", null,
        ))
        val reopened = AppleLyricsLineCorrectionStore(storage)
        assertEquals("하나", reopened.correction("same", 10, 20, "first")?.translation)
        assertEquals("둘", reopened.correction("same", 30, 40, "second")?.translation)
        assertEquals("셋", reopened.correction("same", 50, 60, "third")?.translation)
    }

    @Test
    fun `render lookups keep their same-song cache while editor writes refresh disk`() {
        val storage = MemoryStorage()
        val store = AppleLyricsLineCorrectionStore(storage)
        assertTrue(store.update(
            "same", 10, 20, "first", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "하나",
        ))
        val afterSave = storage.readCount
        repeat(3) {
            assertTrue(store.hasAny("same", AppleLyricsLineCorrectionStore.Field.TRANSLATION))
            assertEquals("하나", store.correction("same", 10, 20, "first")?.translation)
        }
        assertEquals(afterSave, storage.readCount)
        assertTrue(store.update(
            "same", 30, 40, "second", AppleLyricsLineCorrectionStore.Field.TRANSLATION, "둘",
        ))
        assertEquals(afterSave + 1, storage.readCount)
    }
}
