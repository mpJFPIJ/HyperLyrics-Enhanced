/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DiskSongManagerTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-song").toFile()
        DiskSongManager::class.java.getDeclaredField("baseDir").apply {
            isAccessible = true
            set(DiskSongManager, dir)
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `repeated loads return independent copies of the decoded song`() {
        DiskSongManager.save(AppleSong(adamId = "1", name = "disk"))

        val first = DiskSongManager.load("1")!!
        first.name = "mutated by metadata"
        val second = DiskSongManager.load("1")!!

        assertNotSame(first, second)
        assertEquals("disk", second.name)
    }

    @Test
    fun `save and delete invalidate the decoded cache`() {
        DiskSongManager.save(AppleSong(adamId = "2", name = "old"))
        assertEquals("old", DiskSongManager.load("2")?.name)

        DiskSongManager.save(AppleSong(adamId = "2", name = "new"))
        assertEquals("new", DiskSongManager.load("2")?.name)

        DiskSongManager.delete("2")
        assertNull(DiskSongManager.load("2"))
    }

    @Test
    fun `identical saves skip the write and keep the generation`() {
        val song = AppleSong(adamId = "3", name = "same")
        DiskSongManager.saveIfChanged(song)
        val generation = DiskSongManager.generation

        DiskSongManager.saveIfChanged(AppleSong(adamId = "3", name = "same"))
        assertEquals(generation, DiskSongManager.generation)

        DiskSongManager.saveIfChanged(AppleSong(adamId = "3", name = "changed"))
        assertEquals("changed", DiskSongManager.load("3")?.name)
        assertTrue(DiskSongManager.generation > generation)
    }

    @Test
    fun `saved song is served from memory and matches its disk round trip`() {
        val saved = AppleSong(adamId = "4", name = "fresh", duration = 123)
        DiskSongManager.save(saved)
        val diskFile = File(dir, "4.json.gz")
        val bytes = diskFile.readBytes()

        diskFile.delete()
        assertEquals(saved, DiskSongManager.load("4"))

        diskFile.writeBytes(bytes)
        DiskSongManager.delete("4")
        diskFile.writeBytes(bytes)
        assertEquals(saved, DiskSongManager.load("4"))
    }

    @Test
    fun `cache filenames preserve normal IDs but do not allow path components`() {
        assertEquals("1234.json.gz", AppleSongCacheFilePolicy.fileName("1234"))
        assertEquals("spotify:track:abc.json.gz", AppleSongCacheFilePolicy.fileName("spotify:track:abc"))
        val unsafe = AppleSongCacheFilePolicy.fileName("../outside")
        assertTrue(unsafe.startsWith("id_") && unsafe.endsWith(".json.gz"))
        assertEquals(unsafe, AppleSongCacheFilePolicy.fileName("../outside"))
        assertFalse(unsafe.contains('/'))
        assertFalse(unsafe.contains('\\'))
        assertFalse(unsafe == AppleSongCacheFilePolicy.fileName("../different"))
        assertTrue(AppleSongCacheFilePolicy.fileName("/absolute").startsWith("id_"))
        assertTrue(AppleSongCacheFilePolicy.fileName("..\\windows").startsWith("id_"))
        assertTrue(AppleSongCacheFilePolicy.fileName("a\u0000b").startsWith("id_"))
        assertTrue(AppleSongCacheFilePolicy.fileName("한".repeat(100)).startsWith("id_"))
        // A literal ordinary song ID must not impersonate the hash-domain
        // filename assigned to a completely different unsafe song ID.
        val hashLookingId = unsafe.removeSuffix(".json.gz")
        assertFalse(unsafe == AppleSongCacheFilePolicy.fileName(hashLookingId))
    }

    @Test
    fun `unsafe song identities cannot write outside either lyrics cache directory`() {
        val owned = File(dir, "inside").apply { mkdirs() }
        val songs = File(owned, "songs").apply { mkdirs() }
        val missing = File(owned, "missing").apply { mkdirs() }
        DiskSongManager::class.java.getDeclaredField("baseDir").apply {
            isAccessible = true
            set(DiskSongManager, songs)
        }
        DiskSongManager::class.java.getDeclaredField("missingLyricsBaseDir").apply {
            isAccessible = true
            set(DiskSongManager, missing)
        }
        val traversal = "../outside"
        assertTrue(DiskSongManager.save(AppleSong(adamId = traversal, name = "safe")))
        assertTrue(DiskSongManager.saveMissingLyrics(Song(id = traversal)))
        assertFalse(File(owned, "outside.json.gz").exists())
        assertFalse(File(dir, "outside.json.gz").exists())
        assertEquals("safe", DiskSongManager.load(traversal)?.name)
        assertEquals(traversal, DiskSongManager.loadMissingLyrics(traversal)?.id)
        assertTrue(File(songs, AppleSongCacheFilePolicy.fileName(traversal)).exists())
        assertTrue(File(missing, AppleSongCacheFilePolicy.fileName(traversal)).exists())
    }

    @Test
    fun `interrupted staged cache write preserves the last complete disk record`() {
        val target = File(dir, "interrupted.json.gz")
        target.writeText("previous complete record")

        assertThrows(IOException::class.java) {
            AppleSongCacheAtomicFile.replaceWith(target) { output ->
                output.write("partial replacement".toByteArray(Charsets.UTF_8))
                throw IOException("simulated interrupted write")
            }
        }

        assertEquals("previous complete record", target.readText())
        assertEquals(listOf("interrupted.json.gz"), dir.list()?.sorted())
    }

    @Test
    fun `staged cache replacement keeps old bytes visible until publication`() {
        val target = File(dir, "complete.json.gz")
        target.writeText("old")

        AppleSongCacheAtomicFile.replaceWith(target) { output ->
            output.write("new complete record".toByteArray(Charsets.UTF_8))
            assertEquals("old", target.readText())
        }

        assertEquals("new complete record", target.readText())
        assertEquals(listOf("complete.json.gz"), dir.list()?.sorted())
    }
}
