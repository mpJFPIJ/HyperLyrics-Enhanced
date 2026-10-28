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

class AppleLyricsLineCorrectionStoreTest {
    private class MemoryStorage : AppleLyricsLineCorrectionStore.Storage {
        val files = mutableMapOf<String, String>()
        var failNextRead = false

        override fun read(key: String): String? {
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
}
