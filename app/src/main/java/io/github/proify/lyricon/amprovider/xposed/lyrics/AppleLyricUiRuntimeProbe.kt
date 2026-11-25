package io.github.proify.lyricon.amprovider.xposed

import android.text.Spanned
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.bridge.sendAppleDirectBroadcast
import com.juren233.hyperlyricsenhanced.utils.diagnostics.AppleUiTraceTransportPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Narrow, bounded diagnostics for the *actual* Apple 6.5.3 lyric UI pipeline.
 * Never log user lyric text, tokens, keys, or unbounded view snapshots.
 * Unlike the old whole-frame profiler, this samples only a few binding events.
 */
internal object AppleLyricUiRuntimeProbe {
    private val samples = ConcurrentHashMap<String, AtomicInteger>()

    fun record(stage: String, details: String, root: View? = null) =
        recordLazy(stage, root) { details }

    /** Expensive geometry is calculated only for the first bounded samples,
     * never for every subsequently measured RecyclerView holder. */
    fun recordLazy(stage: String, root: View? = null, details: () -> String) {
        val number = samples.computeIfAbsent(stage) { AtomicInteger() }
            .incrementAndGet()
        if (!AppleUiProbeSamplingPolicy.shouldRecord(number)) return
        val viewSummary = if (root == null) "" else viewShapeSummary(root)
        val description = details()
        ProviderLogger.debug(
            "[HLE_UX_TRACE] build=${BuildConfig.HLE_BUILD_RUN} " +
                "stage=$stage sample=$number $description $viewSummary"
        )
        // The patched Apple process is often rootless, so its LSPosed logs
        // cannot be opened with `su` by the separately installed app. Route
        // only allowlisted geometry through an authenticated broadcast; never
        // include a lyric line, raw exception, song ID or API credential.
        if (BuildConfig.DEBUG && root != null) {
            val safe = AppleUiTraceTransportPolicy.encode(
                stage = stage, build = BuildConfig.HLE_BUILD_RUN.toString(),
                details = "sample=$number $description $viewSummary",
            )
            if (safe != null) runCatching {
                root.context.sendAppleDirectBroadcast(
                    Intent(AppleUiTraceTransportPolicy.ACTION)
                        .setPackage(BuildConfig.APPLICATION_ID)
                        .putExtra(AppleUiTraceTransportPolicy.EXTRA_SAFE_TRACE, safe),
                )
            }
        }
    }

    private fun viewShapeSummary(root: View): String {
        var views = 0
        var meaning = 0
        var reading = 0
        var original = 0
        var gradients = 0
        var textSpans = 0
        var paintShaders = 0
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty() && views < 120) {
            val view = pending.removeFirst()
            views++
            if (view.javaClass.simpleName.contains(
                    "AlphaGradient", ignoreCase = true,
                )
            ) gradients++
            if (view is TextView) {
                val name = runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrNull().orEmpty()
                if (name.contains("translation", ignoreCase = true)) meaning++
                if (name.contains("pronunciation", ignoreCase = true)) reading++
                if (name.contains("song_lyrics_word")) original++
                val spans = view.text as? Spanned
                if (spans != null && spans.getSpans(
                        0, spans.length, Any::class.java,
                    ).isNotEmpty()
                ) textSpans++
                if (view.paint.shader != null) paintShaders++
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
            }
        }
        return "uiViews=$views meaningCells=$meaning readingCells=$reading " +
            "sourceCells=$original gradientParents=$gradients " +
            "spannedTexts=$textSpans paintShaders=$paintShaders"
    }
}
