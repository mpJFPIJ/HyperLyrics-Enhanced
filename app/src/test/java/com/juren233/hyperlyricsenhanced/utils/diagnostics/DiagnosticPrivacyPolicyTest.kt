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

    @Test fun overlayCanvasClippingSeparatesInheritedClipFromOurOwnHardClipPrivately() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_paint_clip", build = "12345",
            details = "sample=3 hostH=160 canvasBeforeTop=-20 canvasBeforeBottom=200 " +
                "canvasAfterTop=0 canvasAfterBottom=160 inkTop=-8 inkBottom=173 " +
                "manualLostTop=8 manualLostBottom=13 transformedAncestors=1 " +
                "reading=PRIVATE_HANGUL original=PRIVATE_JAPANESE",
        )!!
        assertTrue(safe.contains("canvasBeforeTop=-20"))
        assertTrue(safe.contains("canvasAfterBottom=160"))
        assertTrue(safe.contains("inkTop=-8"))
        assertTrue(safe.contains("manualLostTop=8 manualLostBottom=13"))
        assertTrue(safe.contains("transformedAncestors=1"))
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
    }

    @Test fun nativeFlexOpacityEvidenceCannotLeakLyricContentOrLoseCriticalFields() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_alpha_path", build = "12345",
            details = "sample=8 sourceWords=2 nativeWordMinPm=250 nativeWordMaxPm=750 " +
                "flexToHolderPm=500 overlayWordMinPm=125 overlayWordMaxPm=375 " +
                "sharedAlphaLayers=1 alphaPathValid=1 sourceWords=999 " +
                "lyric=PRIVATE_JAPANESE reading=PRIVATE_HANGUL",
        )!!
        assertEquals(
            "stage=linewide_alpha_path build=12345 sample=8 sourceWords=2 " +
                "nativeWordMinPm=250 nativeWordMaxPm=750 flexToHolderPm=500 " +
                "overlayWordMinPm=125 overlayWordMaxPm=375 sharedAlphaLayers=1 alphaPathValid=1",
            safe,
        )
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
    }

    @Test fun allTranslationFragmentInkBoundsUseTheSamePrivateClipTransport() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "meaning_paint_clip", build = "12345",
            details = "sourceCells=3 nativeCells=9 sample=2 hostH=280 " +
                "canvasBeforeTop=-15 canvasBeforeBottom=300 " +
                "canvasAfterTop=0 canvasAfterBottom=280 inkTop=-6 inkBottom=294 " +
                "manualLostTop=6 manualLostBottom=14 transformedAncestors=0 " +
                "meaning=PRIVATE_KOREAN lyric=PRIVATE_JAPANESE",
        )!!
        assertTrue(safe.contains("inkTop=-6 inkBottom=294"))
        assertTrue(safe.contains("manualLostTop=6 manualLostBottom=14"))
        assertTrue(safe.contains("transformedAncestors=0"))
        assertFalse(safe.contains("nativeCells="))
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
    }

    @Test fun internalSoftWrapClipProbeKeepsTheReservedRowsGeometryAndNoLyricText() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "softwrap_paint_clip", build = "12345",
            details = "sourceCells=5 nativeCells=8 sample=5 hostH=212 " +
                "canvasBeforeTop=-10 canvasBeforeBottom=225 canvasAfterTop=0 " +
                "canvasAfterBottom=212 inkTop=-3 inkBottom=218 " +
                "manualLostTop=3 manualLostBottom=6 transformedAncestors=1 " +
                "lyrics=SECRET_JAPANESE reading=SECRET_HANGUL",
        )!!
        assertTrue(safe.contains("inkTop=-3 inkBottom=218"))
        assertTrue(safe.contains("manualLostTop=3 manualLostBottom=6"))
        assertTrue(safe.contains("transformedAncestors=1"))
        assertFalse(safe.contains("nativeCells="))
        assertFalse(safe.contains("SECRET"))
    }

    @Test fun nativeFollowDispatchConfirmationCanBeReadRootlesslyWithoutLyricText() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "native_follow_dispatch_unconfirmed", build = "12345",
            details = "sample=2 position=17 duration_ms=750 activeCount=2 " +
                "visibleCount=3 songId=PRIVATE_SONG lyrics=PRIVATE_KOREAN",
        )!!
        assertTrue(safe.contains("position=17"))
        assertTrue(safe.contains("duration_ms=750"))
        assertTrue(safe.contains("activeCount=2"))
        assertTrue(safe.contains("visibleCount=3"))
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
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

    @Test fun fallbackGeometryDistinguishesHostFlexWrapFromWordInternalWrapWithoutLyrics() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_fallback_geometry", build = "12345",
            details = "sample=3 sourceCells=4 flexRows=3 nativeSingleLineWords=7 " +
                "nativeWrappedCells=0 multiWordCells=2 sourcePhoneticSpans=0 " +
                "verifiedProjection=0 rawCellValid=1 geometryPending=0 fallbackGate=7 " +
                "readingChars=18 " +
                "sourceText=PRIVATE_JAPANESE reading=PRIVATE_HANGUL",
        )!!
        assertTrue(safe.contains("sourceCells=4"))
        assertTrue(safe.contains("flexRows=3"))
        assertTrue(safe.contains("nativeSingleLineWords=7"))
        assertTrue(safe.contains("nativeWrappedCells=0"))
        assertTrue(safe.contains("multiWordCells=2"))
        assertTrue(safe.contains("sourcePhoneticSpans=0"))
        assertTrue(safe.contains("fallbackGate=7"))
        assertFalse(safe.contains("readingChars=")) // reason takes the last bounded slot
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
    }

    @Test fun multiChildInternalWrapEvidenceSurvivesRootlessTraceWithoutUserLyrics() {
        val safe = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_multiwrap_geometry", build = "12345",
            details = "sample=2 sourceCells=4 sourceWords=5 flexRows=2 " +
                "nativeWrappedCells=2 wrappedSourceChildren=2 " +
                "wrappedWithPeers=2 wrappedMultiWordChildren=0 " +
                "additionalNativeSoftLines=3 nativeHardBreakLines=1 " +
                "sourcePhoneticSpans=5 " +
                "sourceText=PRIVATE_JAPANESE translation=PRIVATE_KOREAN " +
                "apiKey=PRIVATE_SECRET uiViews=100 sourceCells=999 " +
                "gradientParents=2 hostH=240 readingChars=18",
        )!!
        assertEquals(
            "stage=linewide_multiwrap_geometry build=12345 sample=2 " +
                "sourceCells=4 sourceWords=5 flexRows=2 nativeWrappedCells=2 " +
                "wrappedSourceChildren=2 wrappedWithPeers=2 " +
                "wrappedMultiWordChildren=0 additionalNativeSoftLines=3 " +
                "nativeHardBreakLines=1 sourcePhoneticSpans=5",
            safe,
        )
        assertTrue(safe.contains("wrappedSourceChildren=2"))
        assertTrue(safe.contains("wrappedWithPeers=2"))
        assertTrue(safe.contains("wrappedMultiWordChildren=0"))
        assertTrue(safe.contains("additionalNativeSoftLines=3"))
        assertTrue(safe.contains("nativeHardBreakLines=1"))
        assertTrue(safe.contains("nativeWrappedCells=2"))
        assertFalse(safe.contains("PRIVATE"))
        assertEquals(safe, AppleUiTraceTransportPolicy.accept(safe))
    }

    @Test fun verifiedCrossRowSweepsAreCountedWithoutTransmittingLyrics() {
        val event = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_visual_rows_interleaved", build = "12345",
            details = "sourceCells=5 visualRows=2 crossRowSweepFragments=2 " +
                "original=脳みそ reading=노미소",
        )!!
        assertTrue(event.contains("crossRowSweepFragments=2"))
        assertFalse(event.contains("脳"))
        assertFalse(event.contains("노"))
    }

    @Test fun terminalWrappedPartialRowsAreLabeledWithoutTransmittingLyrics() {
        val event = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_visual_rows_interleaved", build = "12345",
            details = "sourceCells=2 visualRows=2 terminalWrappedCell=1 " +
                "crossRowSweepFragments=0 sourceText=PRIVATE_JAPANESE",
        )!!
        assertTrue(event.contains("terminalWrappedCell=1"))
        assertFalse(event.contains("PRIVATE"))
        assertEquals(event, AppleUiTraceTransportPolicy.accept(event))
    }

    @Test fun rareTerminalWrapUsesAnIndependentPrivacyBoundedDiagnosticStage() {
        // Ordinary visual-row samples can exhaust their stage budget before
        // a rare internally wrapped terminal cell first appears.
        val event = AppleUiTraceTransportPolicy.encode(
            stage = "linewide_terminal_wrap_partial", build = "12345",
            details = "sample=1 sourceCells=3 visualRows=2 terminalWrappedCell=1 " +
                "sourceText=PRIVATE_JAPANESE pronunciation=PRIVATE_HANGUL",
        )!!
        assertEquals(
            "stage=linewide_terminal_wrap_partial build=12345 sample=1 " +
                "sourceCells=3 visualRows=2 terminalWrappedCell=1",
            event,
        )
        assertEquals(event, AppleUiTraceTransportPolicy.accept(event))
    }
}
