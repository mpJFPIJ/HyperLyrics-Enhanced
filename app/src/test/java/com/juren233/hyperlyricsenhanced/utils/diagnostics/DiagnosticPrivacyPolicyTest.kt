package com.juren233.hyperlyricsenhanced.utils.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyPolicyTest {
    @Test fun nativeLyricGeometrySurvivesPrivateUploadWithoutTextOrCredentials() {
        val sanitized = DiagnosticPrivacyPolicy.event(
            "11-24 23:05:13.143", "xposed",
            "[HLE_UX_TRACE] build=45422227602 stage=linewide_measured_geometry sample=2 " +
                "hostW=720 hostH=156 readingTop=141 readingH=52 readingLines=2 " +
                "firstInkTop=145 lastInkBottom=199 wrappedSourceViews=1 maxSourceLines=2 " +
                "lyrics=SECRET_JAPANESE token=github_pat_SECRET",
        )!!
        assertTrue(sanitized.contains("build=45422227602"))
        assertTrue(sanitized.contains("hostH=156"))
        assertTrue(sanitized.contains("readingTop=141"))
        assertTrue(sanitized.contains("lastInkBottom=199"))
        assertTrue(sanitized.contains("wrappedSourceViews=1"))
        assertFalse(sanitized.contains("SECRET"))
        assertFalse(sanitized.contains("lyrics="))
    }

    @Test fun negativeMeasuredInkOverflowIsSafeToPreserveButArbitrarySignedSecretsAreNot() {
        val sanitized = DiagnosticPrivacyPolicy.event(
            "11-24 23:06:00.001", "xposed",
            "stage=linewide_measured_geometry firstInkTop=-8 lastInkBottom=186 " +
                "hostH=156 token=-99999 unknown=-141",
        )!!
        assertTrue(sanitized.contains("firstInkTop=-8"))
        assertTrue(sanitized.contains("lastInkBottom=186"))
        assertFalse(sanitized.contains("token="))
        assertFalse(sanitized.contains("unknown="))
    }

    @Test fun preservesValidatedBuildIdentityWithoutLeakingEmbeddedSecrets() {
        val safe = DiagnosticPrivacyPolicy.event(
            "11-05 19:30:00.123", "xposed",
            "stage=module_loaded buildCommit=374d2b648f685822 buildRun=41837740170 lyrics=PRIVATE",
        )!!
        assertTrue(safe.contains("buildCommit=374d2b648f685822 buildRun=41837740170"))
        assertFalse(safe.contains("PRIVATE"))
    }

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

    @Test fun preservesOnlyKnownSafeProviderErrorReasons() {
        val sanitized = DiagnosticPrivacyPolicy.event(
            "11-04 16:26:44.749", "app",
            "stage=http_rejected code=400 reason=response_format lyrics=secret_key sk=secret",
        )!!
        assertTrue(sanitized.contains("code=400 reason=response_format"))
        assertFalse(sanitized.contains("secret"))
        val unexpected = DiagnosticPrivacyPolicy.event(
            "11-04 16:26:44.749", "app",
            "stage=http_rejected code=400 reason=user_private_sentence",
        )!!
        assertFalse(unexpected.contains("user_private_sentence"))
    }

    @Test fun rootlessUiTraceTransportAcceptsOnlyItsOwnCanonicalSanitizedPayload() {
        val payload = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_measured_geometry",
            build = "45428435021",
            details = "sample=2 hostH=156 readingTop=141 readingH=52 " +
                "firstInkTop=-8 lastInkBottom=199 wrappedSourceViews=1 " +
                "sourceWords=19 lyrics=SECRET_LYRICS token=ghp_SECRET",
        )!!
        assertTrue(payload.contains("build=45428435021"))
        assertTrue(payload.contains("firstInkTop=-8"))
        assertTrue(payload.contains("wrappedSourceViews=1"))
        assertFalse(payload.contains("SECRET"))
        assertEquals(payload, AppleUiTraceTransportPolicy.accept(payload))
        assertNull(AppleUiTraceTransportPolicy.accept("$payload rawLyric=PRIVATE"))
        assertNull(AppleUiTraceTransportPolicy.accept("$payload stage=other_stage"))
        assertNull(AppleUiTraceTransportPolicy.accept("stage=secret lyrics=PRIVATE"))
        assertNull(AppleUiTraceTransportPolicy.accept("stage=foo " + "x".repeat(2000)))
    }

    @Test fun rootlessUiTraceTransportRejectsOversizeInputsAndMalformedStages() {
        assertNull(AppleUiTraceTransportPolicy.encode(
            stage = "bad stage", build = "123", details = "hostH=200",
        ))
        assertNull(AppleUiTraceTransportPolicy.encode(
            stage = "linewide_measured_geometry", build = "123",
            details = "hostH=200 " + "z".repeat(5000),
        ))
    }

    @Test fun sourceInkClippingMeasurementsSurviveSanitizationWithoutLyrics() {
        val safe = DiagnosticPrivacyPolicy.event(
            "11-25 10:20:30.123", "app",
            "stage=linewide_source_ink_geometry hostH=460 nativeInkOverTop=9 " +
                "nativeInkOverBottom=17 nativeHolderOverBottom=5 " +
                "nativeTopClippedCount=2 nativeBottomClippedCount=1 " +
                "nativeShaderCells=6 sourceText=PRIVATE",
        )!!
        assertTrue(safe.contains("nativeInkOverTop=9"))
        assertTrue(safe.contains("nativeInkOverBottom=17"))
        assertTrue(safe.contains("nativeTopClippedCount=2"))
        assertFalse(safe.contains("PRIVATE"))
    }

    @Test fun failedVisualRowProjectionReportsOnlyBoundedLayoutFacts() {
        val event = DiagnosticPrivacyPolicy.event(
            "11-25 10:20:30.123", "app",
            "stage=linewide_fallback_geometry sourceCells=5 nativeWrappedCells=2 " +
                "sourcePhoneticSpans=3 verifiedProjection=1 rawCellValid=1 " +
                "geometryPending=0 readingChars=25 sourceLyrics=PRIVATE",
        )!!
        assertTrue(event.contains("nativeWrappedCells=2"))
        assertTrue(event.contains("sourcePhoneticSpans=3"))
        assertTrue(event.contains("verifiedProjection=1"))
        assertFalse(event.contains("sourceLyrics="))
    }
}
