package com.juren233.hyperlyricsenhanced.utils.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyPolicyTest {
    @Test fun acceptsOnlyCanonicalRepositoryNames() {
        assertEquals("owner/private-logs", DiagnosticPrivacyPolicy.repository("owner/private-logs"))
        assertNull(DiagnosticPrivacyPolicy.repository("https://github.com/owner/repo"))
        assertNull(DiagnosticPrivacyPolicy.repository("owner/repo/../../../other"))
        assertNull(DiagnosticPrivacyPolicy.repository("owner/repo?token=abc"))
        assertNull(DiagnosticPrivacyPolicy.repository("owner/."))
    }

    @Test fun stripsRawLyricsKeysAndUnstructuredContent() {
        val line = "stage=http_io_failed type=SocketTimeoutException lyrics=SECRET_LYRICS " +
            "Authorization=Bearer ghp_SECRET token=github_pat_SECRET"
        val safe = DiagnosticPrivacyPolicy.event("11-03 10:10:00.123", "app", line)!!
        assertTrue(safe.contains("stage=http_io_failed"))
        assertTrue(safe.contains("type=SocketTimeoutException"))
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("Bearer"))
        assertNull(DiagnosticPrivacyPolicy.event("11-03 10:10:00.123", "app", "full private lyric"))
    }

    @Test fun unknownParametersCannotSmuggleSecrets() {
        val safe = DiagnosticPrivacyPolicy.event(
            "11-03 10:10:00.123", "xposed",
            "stage=translation_failed reason=ghp_SECRET api_key=sk-SECRET model=secret",
        )!!
        assertTrue(safe.contains("stage=translation_failed"))
        assertFalse(safe.contains("ghp_"))
        assertFalse(safe.contains("api_key"))
        assertFalse(safe.contains("model="))
    }

    @Test fun snapshotIsBoundedEvenWithHugeInput() {
        val entries = List(3000) {
            "11-03 10:10:00.123 app stage=translation_failed type=SocketTimeoutException"
        }
        val payload = DiagnosticPrivacyPolicy.snapshot(entries)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        assertTrue(payload.lines().size <= 202)
    }
}
