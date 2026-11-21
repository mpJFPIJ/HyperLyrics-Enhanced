package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOnlineSourceReceiptPolicyTest {
    @Test
    fun `stale same song source cannot override a newer pending selection`() {
        assertFalse(shouldAcceptAppleOnlineSourceReceipt(
            songId = "same-song", incomingSource = "NE",
            pendingSongId = "same-song", pendingTargetSource = "QM",
            confirmedSongId = null, confirmedSource = null,
        ))
        assertTrue(shouldAcceptAppleOnlineSourceReceipt(
            songId = "same-song", incomingSource = "QM",
            pendingSongId = "same-song", pendingTargetSource = "QM",
            confirmedSongId = null, confirmedSource = null,
        ))
    }

    @Test
    fun `late prior source remains stale even after newer request was confirmed`() {
        assertFalse(shouldAcceptAppleOnlineSourceReceipt(
            songId = "same-song", incomingSource = "NE",
            pendingSongId = null, pendingTargetSource = null,
            confirmedSongId = "same-song", confirmedSource = "QM",
        ))
        assertTrue(shouldAcceptAppleOnlineSourceReceipt(
            songId = "same-song", incomingSource = "QM",
            pendingSongId = null, pendingTargetSource = null,
            confirmedSongId = "same-song", confirmedSource = "QM",
        ))
    }

    @Test
    fun `unrelated song and unsolicited providers remain unaffected`() {
        assertTrue(shouldAcceptAppleOnlineSourceReceipt(
            songId = "new-song", incomingSource = "NE",
            pendingSongId = "old-song", pendingTargetSource = "QM",
            confirmedSongId = "old-song", confirmedSource = "QM",
        ))
        assertTrue(shouldAcceptAppleOnlineSourceReceipt(
            songId = "new-song", incomingSource = null,
            pendingSongId = "new-song", pendingTargetSource = "QM",
            confirmedSongId = null, confirmedSource = null,
        ))
    }
}
