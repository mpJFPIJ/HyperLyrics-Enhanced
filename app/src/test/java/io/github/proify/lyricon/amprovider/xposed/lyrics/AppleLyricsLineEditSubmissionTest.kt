package io.github.proify.lyricon.amprovider.xposed.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsLineEditSubmissionTest {
    @Test
    fun `rejects editor save after song switch without touching the next song`() {
        var saved = false
        val result = AppleLyricsLineEditSubmission.submit("old-song", "new-song") {
            saved = true
            true
        }

        assertEquals(AppleLyricsLineEditSubmission.Result.SONG_CHANGED, result)
        assertFalse(saved)
        assertFalse(result.canDismiss)
    }

    @Test
    fun `failed persistence keeps editor open with pending text intact`() {
        val result = AppleLyricsLineEditSubmission.submit("song", "song") { false }

        assertEquals(AppleLyricsLineEditSubmission.Result.SAVE_FAILED, result)
        assertFalse(result.canDismiss)
    }

    @Test
    fun `unexpected hook callback failure does not crash the editor or dismiss it`() {
        val result = AppleLyricsLineEditSubmission.submit("song", "song") {
            throw IllegalStateException("host hook temporarily unavailable")
        }

        assertEquals(AppleLyricsLineEditSubmission.Result.SAVE_FAILED, result)
        assertFalse(result.canDismiss)
    }

    @Test
    fun `successful save or reset may dismiss editor`() {
        val result = AppleLyricsLineEditSubmission.submit("song", "song") { true }

        assertEquals(AppleLyricsLineEditSubmission.Result.SAVED, result)
        assertTrue(result.canDismiss)
    }
}
