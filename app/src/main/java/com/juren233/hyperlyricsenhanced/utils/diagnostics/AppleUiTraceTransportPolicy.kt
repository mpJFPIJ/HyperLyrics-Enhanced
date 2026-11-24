package com.juren233.hyperlyricsenhanced.utils.diagnostics

/** A privacy boundary shared by Apple Music's injected hooks and the separately
 * installed Enhanced app. Native word text, song identifiers and raw log lines
 * must never be transported, even on debug builds. */
internal object AppleUiTraceTransportPolicy {
    const val ACTION = "com.juren233.hyperlyricsenhanced.applemusic.UI_TRACE"
    const val EXTRA_SAFE_TRACE = "safe_trace"
    const val APPLE_PACKAGE = "com.apple.android.music"
    private const val MAX_UNTRUSTED_DETAILS = 4096
    private const val MAX_SAFE_PAYLOAD = 1024
    private val stagePattern = Regex("[a-z][a-z0-9_]{1,63}")

    fun encode(stage: String, build: String, details: String): String? {
        if (!stagePattern.matches(stage) || details.length > MAX_UNTRUSTED_DETAILS) return null
        val canonical = DiagnosticPrivacyPolicy.safeEventBody(
            "stage=$stage build=$build $details",
        ) ?: return null
        return accept(canonical)
    }

    /** Re-sanitize on receipt and require byte-for-byte canonical fields.
     * No caller-controlled free text can be appended after the producer filter. */
    fun accept(payload: String?): String? = payload?.takeIf { value ->
        value.length in 1..MAX_SAFE_PAYLOAD &&
            DiagnosticPrivacyPolicy.safeEventBody(value) == value
    }
}
