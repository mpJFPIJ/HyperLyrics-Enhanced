package com.juren233.hyperlyricsenhanced.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricBuildIdentityTest {
    @Test fun signedBuildMustHaveVerifiableCommitAndRun() {
        assertTrue(LyricBuildIdentity("ci", "374d2b648f68", "41837740170").isVerifiable())
        assertFalse(LyricBuildIdentity("ci", "", "41837740170").isVerifiable())
        assertFalse(LyricBuildIdentity("ci", "374d2b648f68", "").isVerifiable())
        assertFalse(LyricBuildIdentity("local", "local", "local").isVerifiable())
    }

    @Test fun stageLoggerDropsPrivateOrUnrecognizedKeysAndReasons() {
        val entry = LyricBuildIdentity.safeStage(
            "reading_finished",
            mapOf("readingLines" to 17, "missingLines" to 5, "secretLyric" to 123, "rawLyrics" to 10),
            "api_key=sk-super-secret",
        )
        assertEquals("stage=reading_finished missingLines=5 readingLines=17", entry)
        assertFalse(entry.contains("secret"))
        assertFalse(entry.contains("rawLyrics"))
    }

    @Test fun stageLogClampsCountAndRejectsUnexpectedStage() {
        assertEquals(
            "stage=row_bound rowBound=0",
            LyricBuildIdentity.safeStage("row_bound", mapOf("rowBound" to -3)),
        )
        assertEquals("stage=invalid_stage", LyricBuildIdentity.safeStage("key=private!", emptyMap()))
    }
}
