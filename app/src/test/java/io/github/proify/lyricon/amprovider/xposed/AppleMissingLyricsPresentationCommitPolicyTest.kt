package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMissingLyricsPresentationCommitPolicyTest {
    private val selectedPointer = Any()

    @Test fun queuedSongAPointerNeverCommitsInsideSongB() {
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            requestedSongId = "A",
            currentQueueSongId = "B",
            requestedPointer = selectedPointer,
            selectedPointer = selectedPointer,
            bindingUnchanged = true,
            fragmentUnchanged = true,
        ))
    }

    @Test fun obsoleteSameSongSourcePointerCannotReplaceNewSelection() {
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            requestedSongId = "A",
            currentQueueSongId = "A",
            requestedPointer = Any(),
            selectedPointer = selectedPointer,
            bindingUnchanged = true,
            fragmentUnchanged = true,
        ))
    }

    @Test fun oldPageBindingOrFragmentCannotAcceptQueuedPointer() {
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            "A", "A", selectedPointer, selectedPointer,
            bindingUnchanged = false, fragmentUnchanged = true,
        ))
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            "A", "A", selectedPointer, selectedPointer,
            bindingUnchanged = true, fragmentUnchanged = false,
        ))
    }

    @Test fun currentSelectedPointerCanCommitAfterQueueIdentityIsVerified() {
        assertTrue(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            requestedSongId = "A",
            currentQueueSongId = "A",
            requestedPointer = selectedPointer,
            selectedPointer = selectedPointer,
            bindingUnchanged = true,
            fragmentUnchanged = true,
        ))
    }

    @Test fun missingPlaybackIdentityOrPointerFailsClosed() {
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            "A", null, selectedPointer, selectedPointer, true, true,
        ))
        assertFalse(AppleMissingLyricsPresentationCommitPolicy.canCommit(
            "A", "A", selectedPointer, null, true, true,
        ))
    }
}
