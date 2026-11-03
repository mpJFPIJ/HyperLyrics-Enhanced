package com.juren233.hyperlyricsenhanced.utils.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticGitHubRequestPolicyTest {
    @Test fun blocksUploadsToPublicAndUnverifiedRepositories() {
        assertTrue(DiagnosticGitHubRequestPolicy.isPrivate(200, "{\"private\":true}"))
        assertFalse(DiagnosticGitHubRequestPolicy.isPrivate(200, "{\"private\":false}"))
        assertFalse(DiagnosticGitHubRequestPolicy.isPrivate(404, "{\"private\":true}"))
        assertFalse(DiagnosticGitHubRequestPolicy.isPrivate(200, "broken"))
        assertFalse(DiagnosticGitHubRequestPolicy.isPrivate(200, "{}"))
    }

    @Test fun updateRequiresExistingBlobSha() {
        assertEquals(null, DiagnosticGitHubRequestPolicy.existingSha(404, null))
        assertEquals("0123456789abcdef0123456789abcdef01234567", DiagnosticGitHubRequestPolicy.existingSha(
            200, "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}",
        ))
        assertEquals(null, DiagnosticGitHubRequestPolicy.existingSha(200, "{\"sha\":\"bad\"}"))
    }
}
