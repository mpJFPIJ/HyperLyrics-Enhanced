package com.juren233.hyperlyricsenhanced.utils.diagnostics

/** Only structured, allowlisted diagnostics leave the phone; never raw log lines. */
internal object DiagnosticPrivacyPolicy {
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_EVENTS = 200
    private val repositoryPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    private val stagePattern = Regex("(?:^|\\s)stage=([a-z][a-z0-9_]{1,63})(?=\\s|$)")
    private val fieldPattern = Regex("(?:^|\\s)([A-Za-z_]+)=([A-Za-z0-9_.-]{1,70})(?=\\s|$)")
    private val numericFields = setOf(
        "code", "attempt", "lines", "translated", "expected", "pronounced", "pronouncedLines",
        "read_timeout_ms", "duration_ms", "max_tokens", "cooldown_ms", "remaining_ms",
        "originalLines", "translationLines", "readingLines", "alignedLines", "lineWideLines",
        "missingLines", "rowBound", "rowOverflow", "holderWidth", "holderHeight",
        "holderChildren", "holderGroup", "position",
    )
    private val safeType = Regex("[A-Za-z][A-Za-z0-9]{0,45}(?:Exception|Error)")
    private val safeReasons = setOf(
        "network_error", "retry_exhausted", "empty_response", "quality_invalid",
        "cache", "missing_api_key", "no_translatable_lines", "disabled", "timeout",
        "remote_unavailable", "config_incomplete", "invalid_request", "stale",
        "response_format", "model_unavailable", "invalid_argument", "unspecified",
        "temperature", "top_p", "max_tokens", "presence_penalty",
        "frequency_penalty", "api_key_invalid", "quota",
    )
    private val timePattern = Regex("[0-9 T:.-]{1,35}")

    fun repository(value: String): String? = value.trim().takeIf { candidate ->
        repositoryPattern.matches(candidate) && candidate.substringAfter('/') !in setOf(".", "..") &&
            candidate.substringBefore('/') !in setOf(".", "..")
    }

    fun event(timestamp: String, source: String, message: String): String? {
        if (!timePattern.matches(timestamp) || source !in setOf("app", "xposed")) return null
        val stage = stagePattern.find(message)?.groupValues?.get(1) ?: return null
        val extras = fieldPattern.findAll(message).mapNotNull { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            when {
                key in numericFields && value.all(Char::isDigit) -> "$key=$value"
                key == "buildCommit" && (value == "local" || value.matches(Regex("[0-9a-f]{12,40}"))) -> "$key=$value"
                key == "buildRun" && (value == "local" || value.matches(Regex("[0-9]{1,20}"))) -> "$key=$value"
                key == "type" && safeType.matches(value) -> "$key=$value"
                key == "reason" && (value in safeReasons || Regex("http_[1-5][0-9]{2}").matches(value)) -> "$key=$value"
                else -> null
            }
        }.take(12).joinToString(" ")
        return "$timestamp $source stage=$stage" + if (extras.isEmpty()) "" else " $extras"
    }

    fun snapshot(events: List<String>): String {
        val output = StringBuilder("HyperLyrics Enhanced structured diagnostics (no lyrics or credentials)\n")
        var count = 0
        for (event in events) {
            if (count >= MAX_EVENTS) break
            val line = event.take(512) + "\n"
            if ((output.length + line.length) * 4 > MAX_BYTES) break
            output.append(line)
            count++
        }
        return output.toString()
    }
}
