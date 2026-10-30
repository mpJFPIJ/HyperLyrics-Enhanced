package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
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
    @Test fun changedLyricsOrSongIdMustNotShareRequest() {
        val base = Song(id = "123", lyrics = listOf(RichLyricLine(text = "First")))
        assertNotEquals(AppleMusicAiRequestIdentity.key(base),
            AppleMusicAiRequestIdentity.key(base.copy(id = "124")))
        assertNotEquals(AppleMusicAiRequestIdentity.key(base),
            AppleMusicAiRequestIdentity.key(base.copy(lyrics = listOf(RichLyricLine(text = "Second")))))
    }
}
