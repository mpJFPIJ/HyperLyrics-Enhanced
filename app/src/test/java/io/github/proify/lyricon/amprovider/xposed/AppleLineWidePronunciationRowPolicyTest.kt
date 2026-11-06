package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLineWidePronunciationRowPolicyTest {
    @Test
    fun `matches original karaoke source without depending on visual word breaks`() {
        assertEquals(
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目 駄目 脳みその中から"),
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目駄目脳 みそ の 中 から"),
        )
        assertFalse(
            AppleLineWidePronunciationRowPolicy.sourceKey("駄目脳") ==
                AppleLineWidePronunciationRowPolicy.sourceKey("駄目駄目脳"),
        )
    }

    @Test
    fun `never shows a stale or hidden reading on a recycled lyric holder`() {
        val payload = AppleLineWidePronunciationPayload(
            songId = "a", lineKey = "123", sourceText = "みその中から",
            pronunciation = "미소노 나카카라", revision = 5L,
        )
        assertTrue(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, true))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "b", 5L, true))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 6L, true))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, false))
    }

    @Test
    fun `full width reading must wrap instead of truncating or splitting per native word`() {
        for (width in listOf(320, 360, 430, 968)) {
            assertEquals(width, AppleLineWidePronunciationRowPolicy.fullWidth(width))
        }
        assertEquals(-1, AppleLineWidePronunciationRowPolicy.fullWidth(0))
        assertNull(AppleLineWidePronunciationRowPolicy.readableText("  \n  "))
        assertEquals(
            "노미소노 나카카라 키코에루",
            AppleLineWidePronunciationRowPolicy.readableText("노미소노  나카카라\n 키코에루"),
        )
    }

    @Test
    fun `song switch and holder reuse do not leak old Korean text`() {
        val state = AppleLyricsPronunciationState()
        val holder = Any()
        val original = AppleLineWidePronunciationPayload(
            "song-a", "line-1", "みその中から", "미소노 나카카라", 1L,
        )
        state.rememberLineWide(original, holder)
        assertEquals(
            original,
            state.lineWideForHolder(holder, "song-a", "みその中から", 1L),
        )

        state.beginHolderBinding(holder)
        assertNull(state.lineWideForHolder(holder, "song-b", "みその中から", 1L))
        assertNull(state.lineWideForHolder(holder, "song-a", "別の歌", 1L))

        val newer = original.copy(songId = "song-b", lineKey = "line-2", revision = 2L)
        state.rememberLineWide(newer, holder)
        assertEquals(newer, state.lineWideForHolder(holder, "song-b", "みその中から", 2L))
        assertNull(state.lineWideForHolder(holder, "song-a", "みその中から", 1L))
    }

    @Test
    fun `same looking native lyric with conflicting readings is never guessed`() {
        val state = AppleLyricsPronunciationState()
        val first = AppleLineWidePronunciationPayload(
            "song", "a", "生きる", "이키루", 3L,
        )
        state.rememberLineWide(first, null)
        state.rememberLineWide(first.copy(lineKey = "b", pronunciation = "세이키루"), null)
        assertNull(state.lineWideForHolder(null, "song", "生きる", 3L))
    }
}
