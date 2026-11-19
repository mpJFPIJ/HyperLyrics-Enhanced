package com.juren233.hyperlyricsenhanced.common.diagnostics

/** Build evidence shared by the installed app and the Xposed-loaded module. */
internal data class LyricBuildIdentity(
    val label: String,
    val commit: String,
    val ciRun: String,
) {
    fun isVerifiable(): Boolean =
        commit.matches(Regex("[0-9a-f]{12,40}")) && ciRun.matches(Regex("[0-9]{1,20}"))

    fun logValue(): String = if (isVerifiable()) {
        "buildCommit=$commit buildRun=$ciRun"
    } else {
        "buildCommit=local buildRun=local"
    }

    companion object {
        private val stagePattern = Regex("[a-z][a-z0-9_]{1,63}")
        private val allowedCounts = setOf(
            "originalLines", "translationLines", "readingLines", "alignedLines",
            "lineWideLines", "missingLines", "rowBound", "rowOverflow",
            "holderWidth", "holderHeight", "holderChildren", "holderGroup", "position",
        )
        private val allowedReasons = setOf(
            "missing", "unsupported", "source_mismatch", "boundary_split",
            "holder_unavailable", "stale", "hidden", "remote_unavailable",
        )

        /** No user-controlled lyric, song, URL, key, or model string may reach diagnostic logs. */
        fun safeStage(stage: String, stats: Map<String, Int>, reason: String? = null): String {
            val cleanStage = stage.takeIf(stagePattern::matches) ?: "invalid_stage"
            val counters = stats.asSequence().filter { it.key in allowedCounts }
                .sortedBy { it.key }
                .joinToString(" ") { (key, value) -> "$key=${value.coerceIn(0, 100_000)}" }
            val cleanReason = reason?.takeIf { it in allowedReasons }
            return buildString {
                append("stage=").append(cleanStage)
                if (counters.isNotEmpty()) append(' ').append(counters)
                if (cleanReason != null) append(" reason=").append(cleanReason)
            }
        }
    }
}
