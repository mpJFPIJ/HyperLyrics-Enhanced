package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsSyncOffsetTest {
    @Test fun `positive offset delays lyrics without changing playback clock`() {
        val playbackMs = 12_000L
        assertEquals(11_500L, AppleLyricsSyncOffset.lyricPositionMs(playbackMs, 500))
        assertEquals(12_000L, playbackMs)
        assertEquals(12_500L, AppleLyricsSyncOffset.lyricPositionMs(playbackMs, -500))
    }

    @Test fun `time adjustment clamps beginning and extreme values safely`() {
        assertEquals(0L, AppleLyricsSyncOffset.lyricPositionMs(200L, 500))
        assertEquals(0L, AppleLyricsSyncOffset.lyricPositionMs(-1L, 0))
        assertEquals(Long.MAX_VALUE, AppleLyricsSyncOffset.lyricPositionMs(Long.MAX_VALUE, -500))
        assertEquals(10_000, AppleLyricsSyncOffset.nextOffsetMs(9_900, 500))
        assertEquals(-10_000, AppleLyricsSyncOffset.nextOffsetMs(-9_900, -500))
        assertEquals(0, AppleLyricsSyncOffset.nextOffsetMs(500, -500))
    }

    @Test fun `native renderer keeps its raw clock while supplement uses offset`() {
        assertEquals(12_000L, AppleLyricsSyncOffset.positionForSurface(12_000L, 500, false))
        assertEquals(11_500L, AppleLyricsSyncOffset.positionForSurface(12_000L, 500, true))
        assertEquals(12_500L, AppleLyricsSyncOffset.positionForSurface(12_000L, -500, true))
    }

    @Test fun `controls are hidden when the active renderer cannot apply the offset`() {
        assertTrue(AppleLyricsSyncOffset.canAdjustSupplement(true, false, false))
        assertFalse(AppleLyricsSyncOffset.canAdjustSupplement(false, false, false))
        assertFalse(AppleLyricsSyncOffset.canAdjustSupplement(true, true, false))
        assertFalse(AppleLyricsSyncOffset.canAdjustSupplement(true, false, true))
    }

    @Test fun `song-specific settings survive recreation and do not leak song ids`() {
        val entries = mutableMapOf<String, Int>()
        val preferences = object : AppleLyricsSongOffsetStore.Preferences {
            override fun read(key: String): Int = entries[key] ?: 0
            override fun write(key: String, value: Int?) {
                if (value == null) entries.remove(key) else entries[key] = value
            }
        }
        val first = AppleLyricsSongOffsetStore(preferences)
        assertTrue(first.set("12345", 500))
        assertTrue(first.set("67890", -100))
        assertEquals(500, AppleLyricsSongOffsetStore(preferences).get("12345"))
        assertEquals(-100, first.get("67890"))
        assertEquals(0, first.get("unknown"))
        assertFalse(entries.keys.any { it.contains("12345") || it.contains("67890") })
        assertFalse(first.set("   ", 100))
        assertEquals(500, first.get("12345"))
        assertTrue(first.set("12345", 0))
        assertEquals(0, first.get("12345"))
        assertEquals(1, entries.size)
    }

    @Test fun `persisted invalid offsets are normalized on read`() {
        val values = mutableMapOf<String, Int>()
        val preferences = object : AppleLyricsSongOffsetStore.Preferences {
            override fun read(key: String): Int = values[key] ?: 0
            override fun write(key: String, value: Int?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val store = AppleLyricsSongOffsetStore(preferences)
        store.set("song", Int.MAX_VALUE)
        assertEquals(10_000, store.get("song"))
        assertEquals(10_000, AppleLyricsSyncOffset.nextOffsetMs(Int.MAX_VALUE, 500))
        assertEquals(-10_000, AppleLyricsSyncOffset.nextOffsetMs(Int.MIN_VALUE, -500))
    }
}
