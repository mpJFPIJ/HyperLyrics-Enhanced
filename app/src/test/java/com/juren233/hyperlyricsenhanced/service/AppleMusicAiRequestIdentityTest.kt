package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppleMusicAiRequestIdentityTest {
    @Test fun sameSourceLyricsWithUpdatedOverlaysShareRequest() {
        val base = Song(id = "123", name = "Song", artist = "Artist", lyrics = listOf(
            RichLyricLine(text = "君の名は"),
            RichLyricLine(text = "ありがとう"),
        ))
        val changed = base.copy(lyrics = listOf(
            RichLyricLine(text = "君の名は", translation = "너의 이름은", roma = "키미노 나와"),
            RichLyricLine(text = "ありがとう", translation = "고마워"),
        ))
        assertEquals(AppleMusicAiRequestIdentity.key(base), AppleMusicAiRequestIdentity.key(changed))
    }
    @Test fun aRevisedNativeClockOrWordLayoutSharesRemoteWorkButRequiresItsOwnDelivery() {
        val line = RichLyricLine(begin = 100, end = 700, text = "君の名は",
            words = listOf(LyricWord(begin = 100, end = 700, text = "君の名は")))
        val song = Song(id = "123", lyrics = listOf(line))
        val original = AppleMusicAiDelivery(song)
        val moved = AppleMusicAiDelivery(song.copy(lyrics = listOf(line.copy(begin = 1100, end = 1700))))
        val split = AppleMusicAiDelivery(song.copy(lyrics = listOf(line.copy(words = listOf(
            LyricWord(begin = 100, end = 400, text = "君の"), LyricWord(begin = 400, end = 700, text = "名は"),
        )))))
        assertEquals(original.semanticKey, moved.semanticKey)
        assertEquals(original.semanticKey, split.semanticKey)
        assertTrue(original.canShareInference(moved))
        val modelResult = line.copy(translation = "너의 이름은")
        val projected = AppleMusicAiTranslationPolicy.applyGeneratedFields(moved.song.lyrics!!.single(), modelResult)
        assertEquals(1100L, projected.begin)
        assertEquals(1700L, projected.end)
        assertEquals("너의 이름은", projected.translation)
        assertEquals(split.song.lyrics!!.single().words,
            AppleMusicAiTranslationPolicy.applyGeneratedFields(split.song.lyrics!!.single(), modelResult).words)
        assertNotEquals(original, moved)
        assertNotEquals(original, split)
        assertEquals(original, AppleMusicAiDelivery(song.deepCopy()))
    }

    @Test fun changedLyricsOrSongIdMustNotShareRequest() {
        val base = Song(id = "123", lyrics = listOf(RichLyricLine(text = "First")))
        assertNotEquals(AppleMusicAiRequestIdentity.key(base),
            AppleMusicAiRequestIdentity.key(base.copy(id = "124")))
        assertNotEquals(AppleMusicAiRequestIdentity.key(base),
            AppleMusicAiRequestIdentity.key(base.copy(lyrics = listOf(RichLyricLine(text = "Second")))))
    }
}
