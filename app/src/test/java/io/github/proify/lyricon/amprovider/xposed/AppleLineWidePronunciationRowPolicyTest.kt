package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLineWidePronunciationRowPolicyTest {
    @Test
    fun `reading is inserted directly after source cells rather than after later translation`() {
        assertEquals(
            3,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "ダーリンベイビー",
                listOf("ダー", "リン", "ベイビー", "달링, 베이비"),
            ),
        )
        assertEquals(
            4,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "駄目駄目脳みそ",
                listOf("駄目", "駄目", "脳", "みそ", "안 돼"),
            ),
        )
    }

    @Test
    fun `punctuation cells stay with Japanese line before Hangul reading`() {
        assertEquals(
            2,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "「やめろ！」", listOf("やめろ", "！", "그만둬"),
            ),
        )
        assertEquals(
            2,
            AppleLineWidePronunciationRowPolicy.insertionIndex(
                "ラブ！と", listOf("ラブ", "！と", "사랑"),
            ),
        )
    }

    @Test
    fun `unmatched or ambiguous source cannot receive another line reading`() {
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリンベイビー", listOf("駄目", "달링"),
        ))
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリン", listOf("ダーリン달링"),
        ))
        assertNull(AppleLineWidePronunciationRowPolicy.insertionIndex(
            "ダーリン", emptyList(),
        ))
    }
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
        assertEquals(
            AppleLineWidePronunciationRowPolicy.sourceKey("「やめろ！」"),
            AppleLineWidePronunciationRowPolicy.sourceKey("やめろ"),
        )
    }

    @Test
    fun `never shows a stale or hidden reading on a recycled lyric holder`() {
        val payload = AppleLineWidePronunciationPayload(
            songId = "a", lineKey = "123", sourceText = "みその中から",
            pronunciation = "미소노 나카카라", revision = 5L, sourcePriority = 1,
        )
        assertTrue(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "b", 5L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 6L, true, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, false, 1))
        assertFalse(AppleLineWidePronunciationRowPolicy.shouldDisplay(payload, "a", 5L, true, 2))
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

    @Test
    fun `English translation after Japanese source must not hide a valid Hangul reading`() {
        val state = AppleLyricsPronunciationState()
        val payload = AppleLineWidePronunciationPayload(
            "song", "line", "ダーリンベイビー", "다아린 베이비", 7L,
        )
        state.rememberLineWide(payload, null)
        val cells = listOf("ダー", "リン", "ベイビー", "Darling baby")
        assertEquals(3, AppleLineWidePronunciationRowPolicy.insertionIndex(payload.sourceText, cells))
        assertEquals(
            3,
            AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex(payload.sourceText, cells),
        )
        assertEquals(
            payload,
            state.lineWideForHolder(null, "song", null, 7L, cells),
        )
    }

    @Test
    fun `one cached short reading cannot be inserted inside a longer Japanese lyric`() {
        val state = AppleLyricsPronunciationState()
        val short = AppleLineWidePronunciationPayload("song", "short", "君", "키미", 7L)
        state.rememberLineWide(short, null)
        val differentLine = listOf("君", "の", "心", "Your heart")
        assertEquals(1, AppleLineWidePronunciationRowPolicy.insertionIndex("君", differentLine))
        assertNull(AppleLineWidePronunciationRowPolicy.verifiedInsertionIndex("君", differentLine))
        assertNull(state.lineWideForHolder(null, "song", null, 7L, differentLine))
    }

    @Test
    fun `multiple matching source prefixes are ambiguous when holder is unavailable`() {
        val state = AppleLyricsPronunciationState()
        state.rememberLineWide(
            AppleLineWidePronunciationPayload("song", "short", "ダー", "다아", 7L), null,
        )
        state.rememberLineWide(
            AppleLineWidePronunciationPayload(
                "song", "long", "ダーリンベイビー", "다아린 베이비", 7L,
            ), null,
        )
        assertNull(state.lineWideForHolder(
            null, "song", null, 7L,
            listOf("ダー", "リン", "ベイビー", "Darling baby"),
        ))
    }

    @Test
    fun `official or newly aligned words remove a previous full line candidate`() {
        val state = AppleLyricsPronunciationState()
        val source = "脳みその中から"
        val payload = AppleLineWidePronunciationPayload(
            "song", "a", source, "노미소노 나카카라", 4L,
        )
        state.rememberLineWide(payload, null)
        assertEquals(payload, state.lineWideForHolder(null, "song", source, 4L))
        state.forgetLineWide("song", source)
        assertNull(state.lineWideForHolder(null, "song", source, 4L))
    }
}
