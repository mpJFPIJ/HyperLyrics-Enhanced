package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStat
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStatsCodec
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation
import com.juren233.hyperlyricsenhanced.lyric.model.LyricPronunciationSpan

/** Checks a receipt's declared provider against the latest explicit song selection. */
internal fun shouldAcceptAppleOnlineSourceReceipt(
    songId: String?,
    incomingSource: String?,
    pendingSongId: String?,
    pendingTargetSource: String?,
    confirmedSongId: String?,
    confirmedSource: String?,
): Boolean {
    val expected = pendingTargetSource.takeIf { pendingSongId == songId }
        ?: confirmedSource.takeIf { confirmedSongId == songId }
    // Older or standalone providers may not declare a source. Such payloads
    // cannot be proven stale by source identity alone; retain old behavior.
    return incomingSource.isNullOrBlank() || expected.isNullOrBlank() || incomingSource == expected
}

internal class AppleNativeOnlineTranslationStore {
    private data class TimingKey(
        val begin: Long,
        val end: Long,
    )

    private data class LineKey(
        val timing: TimingKey,
        val text: String,
    )

    private data class Content(
        val translation: String?,
        val generatedTranslation: String?,
        val pronunciation: String?,
        val wordPronunciations: List<LyricWordPronunciation>?,
        val pronunciationSpans: List<LyricPronunciationSpan>?,
        val pronunciationStatus: String?,
    )

    private data class Entry(
        val text: String,
        val content: Content,
    )

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val translationsByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val hasGeneratedTranslation: Boolean,
        val hasPronunciation: Boolean,
        val hasHangulPronunciation: Boolean,
        val translationSource: String?,
        val pronunciationSource: String?,
        val translationMatchStats: Map<String, OnlineTranslationMatchStat>,
        val pronunciationMatchStats: Map<String, OnlineTranslationMatchStat>,
    )

    @Volatile
    private var overlay: Overlay? = null

    /**
     * A genuine standalone-AI callback is tracked separately from immediately
     * available third-party phonetics. A later provider refresh must not
     * overwrite the completed AI reading or Korean translation.
     */
    @Volatile
    private var aiOverlay: Overlay? = null

    @Volatile
    private var contentRevision = 0L

    private var nextReceipt = 0L
    private var lastAcceptedDirectReceipt = 0L

    @Synchronized fun nextDirectReceipt(): Long = ++nextReceipt

    /** Cache acceptance is not source selection or successful View binding. */
    data class Receipt(
        val updated: Boolean,
        val displayContentChanged: Boolean,
        val update: AppleLyricsPresentationUpdate,
        /** False only when a later direct publication has already won. */
        val accepted: Boolean = true,
    )

    fun update(song: Song): Boolean = receive(song).updated

    /**
     * Comparison, mutation and revision capture share the store monitor.
     * Menu metadata can change independently. A presentation revision invalidates bound
     * rows, so advance it only when those rows also need a rebind.
     */
    @Synchronized
    fun receive(
        song: Song,
        preserveExistingPronunciation: Boolean = false,
        directReceipt: Long? = null,
    ): Receipt {
        if (directReceipt != null && directReceipt <= lastAcceptedDirectReceipt) {
            return Receipt(false, false, AppleLyricsPresentationUpdate(song.id, contentRevision), accepted = false)
        }
        val previousAi = aiOverlay
        val candidate = buildOverlay(song, standalone = preserveExistingPronunciation)
        if (candidate != null) {
            if (directReceipt != null) lastAcceptedDirectReceipt = directReceipt
            if (aiOverlay?.songId != candidate.songId) aiOverlay = null
            if (preserveExistingPronunciation) {
                // A later translation-only response must not erase the exact
                // source offsets captured by the earlier AI pronunciation.
                aiOverlay = aiOverlay?.let { preservePronunciation(candidate, it) } ?: candidate
            }
        }
        val next = if (preserveExistingPronunciation && candidate != null &&
            overlay?.songId == candidate.songId
        ) {
            preservePronunciation(candidate, requireNotNull(overlay))
        } else candidate
        // AI-first rows read aiOverlay even if the general provider already supplied identical text.
        val aiContentChanged = previousAi?.exactContent != aiOverlay?.exactContent
        val displayChanged = next != null &&
            (aiContentChanged || overlay?.songId != next.songId || overlay?.exactContent != next.exactContent)
        val updated = next != null && (overlay != next || previousAi != aiOverlay)
        if (updated) {
            overlay = next
            if (displayChanged) contentRevision += 1
        }
        return Receipt(
            updated = updated,
            displayContentChanged = displayChanged,
            update = AppleLyricsPresentationUpdate(song.id, contentRevision),
        )
    }

    /**
     * A translation-only AI callback must not clear pronunciation supplied by
     * an earlier online provider. Normal source-switch receipts still replace
     * the overlay, so intentional source changes retain their old semantics.
     */
    private fun preservePronunciation(next: Overlay, previous: Overlay): Overlay {
        val merged = next.exactContent.mapValues { (key, content) ->
            val exactPreviousContent = previous.exactContent[key]
            val previousContent = exactPreviousContent
                ?: previous.translationsByTiming[key.timing]?.singleOrNull()?.takeIf {
                    val identity = normalizedSourceIdentity(key.text)
                    identity.isNotEmpty() && normalizedSourceIdentity(it.text) == identity
                }?.content
            val existing = previousContent?.pronunciation
            // Prefer requested Hangul display when AI provides it, while
            // preserving older romanization whenever no Hangul was returned.
            // The source lyrics and Apple's native track remain unchanged.
            val displayReading = content.pronunciation
                ?.takeIf(RomanizationPolicy::isHangulPronunciation)
                ?: existing ?: content.pronunciation
            val keptPrevious = displayReading == existing &&
                !RomanizationPolicy.isHangulPronunciation(content.pronunciation)
            content.copy(
                pronunciation = displayReading,
                wordPronunciations = if (keptPrevious) exactPreviousContent?.wordPronunciations
                    else content.wordPronunciations,
                pronunciationSpans = if (keptPrevious) exactPreviousContent?.pronunciationSpans
                    else content.pronunciationSpans,
                pronunciationStatus = if (keptPrevious) exactPreviousContent?.pronunciationStatus
                    else content.pronunciationStatus,
            )
        }
        val hasPronunciation = merged.values.any { it.pronunciation != null }
        return next.copy(
            exactContent = merged,
            translationsByTiming = merged.entries.groupBy(
                keySelector = { it.key.timing },
                valueTransform = { Entry(it.key.text, it.value) },
            ),
            hasPronunciation = hasPronunciation,
            hasHangulPronunciation = merged.values.any {
                RomanizationPolicy.isHangulPronunciation(it.pronunciation)
            },
            pronunciationSource = next.pronunciationSource
                ?: previous.pronunciationSource.takeIf { hasPronunciation },
            pronunciationMatchStats = if (next.pronunciationMatchStats.isEmpty() && hasPronunciation) {
                previous.pronunciationMatchStats
            } else next.pronunciationMatchStats,
        )
    }

    private fun buildOverlay(song: Song, standalone: Boolean): Overlay? {
        val songId = song.id?.takeIf(String::isNotBlank) ?: return null
        val entries = song.lyrics.orEmpty().mapNotNull { line ->
            val translation = sanitizeContent(line.translation)
            val generated = when {
                !standalone -> null
                line.metadata?.getBoolean(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION) == true ->
                    sanitizeContent(line.metadata?.getString(LyricMetadataKeys.STANDALONE_GENERATED_TRANSLATION))
                else -> translation
            }
            val content = Content(
                translation = translation,
                generatedTranslation = generated,
                pronunciation = RomanizationPolicy.sanitizeSupplement(line.text, line.roma),
                wordPronunciations = line.pronunciationWords,
                pronunciationSpans = line.pronunciationSpans,
                pronunciationStatus = line.pronunciationStatus,
            )
            if (content.translation == null && content.generatedTranslation == null && content.pronunciation == null &&
                content.pronunciationStatus == null
            ) {
                return@mapNotNull null
            }
            val timing = TimingKey(line.begin, line.end)
            LineKey(timing, normalizeText(line.text)) to content
        }
        if (entries.isEmpty()) return null
        return Overlay(
            songId = songId,
            exactContent = entries.toMap(),
            translationsByTiming = entries.groupBy(
                keySelector = { it.first.timing },
                valueTransform = { Entry(it.first.text, it.second) },
            ),
            hasTranslation = entries.any { it.second.translation != null },
            hasGeneratedTranslation = entries.any { it.second.generatedTranslation != null },
            hasPronunciation = entries.any { it.second.pronunciation != null },
            hasHangulPronunciation = entries.any {
                RomanizationPolicy.isHangulPronunciation(it.second.pronunciation)
            },
            translationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
            pronunciationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE),
            translationMatchStats = OnlineTranslationMatchStatsCodec.decode(
                song.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS)
            ),
            pronunciationMatchStats = OnlineTranslationMatchStatsCodec.decode(
                song.metadata?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS)
            ),
        )
    }

    @Synchronized
    fun clear(songId: String? = null): Boolean {
        val current = overlay ?: return false
        if (!songId.isNullOrBlank() && current.songId != songId) return false
        overlay = null
        aiOverlay = null
        contentRevision += 1
        return true
    }

    fun revision(): Long = contentRevision

    @Synchronized
    fun isCurrentRevision(songId: String?, revision: Long): Boolean =
        contentRevision == revision &&
            !songId.isNullOrBlank() &&
            overlay?.songId == songId

    fun hasAiTranslation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            aiOverlay?.let { it.songId == songId && it.hasGeneratedTranslation } == true

    fun hasAiHangulPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            aiOverlay?.let { it.songId == songId && it.hasHangulPronunciation } == true

    fun aiTranslation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text, aiOverlay)?.generatedTranslation

    fun aiHangulPronunciation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text, aiOverlay)
        ?.pronunciation?.takeIf(RomanizationPolicy::isHangulPronunciation)

    fun aiHangulPronunciationWords(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): List<LyricWordPronunciation>? = content(songId, begin, end, text, aiOverlay)
        ?.takeIf { RomanizationPolicy.isHangulPronunciation(it.pronunciation) }
        ?.wordPronunciations

    fun aiHangulPronunciationSpans(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): List<LyricPronunciationSpan>? = content(songId, begin, end, text, aiOverlay)
        ?.takeIf { RomanizationPolicy.isHangulPronunciation(it.pronunciation) }
        ?.pronunciationSpans

    /** A provider switch must not throw away valid AI/native source↔sound anchors
     * when the chosen Hangul says *exactly* the same thing. Never accept a
     * text-only/refrain match or partial dictionary map as a complete row plan. */
    fun soundMatchedPronunciationSpans(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
        selectedPronunciation: String?,
    ): List<LyricPronunciationSpan>? {
        val source = text?.takeIf(String::isNotBlank) ?: return null
        val chosen = selectedPronunciation
            ?.takeIf(RomanizationPolicy::isHangulPronunciation) ?: return null
        // Hangul Jamo and digits may represent sounds that would disappear
        // from a syllables-only comparison. Only punctuation/spaces are silent.
        fun onlyCompleteHangulSyllables(value: String): Boolean =
            value.all { !it.isLetterOrDigit() || it in '가'..'힣' }
        if (!onlyCompleteHangulSyllables(chosen)) return null
        val expectedSound = chosen.filter { it in '가'..'힣' }
        if (expectedSound.isEmpty()) return null
        for (candidate in listOfNotNull(
            content(songId, begin, end, source, aiOverlay),
            content(songId, begin, end, source, overlay),
        )) {
            if (!RomanizationPolicy.isHangulPronunciation(candidate.pronunciation) ||
                !onlyCompleteHangulSyllables(candidate.pronunciation.orEmpty())
            ) continue
            val candidateSound = candidate.pronunciation.orEmpty().filter { it in '가'..'힣' }
            if (candidateSound != expectedSound) continue
            val spans = candidate.pronunciationSpans.orEmpty()
            if (spans.isEmpty()) continue
            val covered = BooleanArray(source.length)
            var cursor = 0
            val sound = StringBuilder(expectedSound.length)
            val tokenEnds = mutableSetOf<Int>()
            var valid = true
            for (span in spans) {
                if (span.start < cursor || span.start < 0 ||
                    span.end <= span.start || span.end > source.length ||
                    source.substring(span.start, span.end) != span.source
                ) {
                    valid = false
                    break
                }
                val syllables = span.hangul.filter { it in '가'..'힣' }
                if (syllables.isEmpty() || !onlyCompleteHangulSyllables(span.hangul)) {
                    valid = false
                    break
                }
                sound.append(syllables)
                tokenEnds += sound.length
                for (index in span.start until span.end) covered[index] = true
                cursor = span.end
            }
            if (!valid || sound.toString() != expectedSound || source.indices.any {
                    source[it].isLetterOrDigit() && !covered[it]
                }
            ) continue
            var sounded = 0
            val hasFalseSpace = chosen.indices.any { index ->
                val char = chosen[index]
                if (char in '가'..'힣') sounded++
                char.isWhitespace() && sounded in 1 until expectedSound.length &&
                    sounded !in tokenEnds
            }
            if (!hasFalseSpace) return spans
        }
        return null
    }

    fun aiHangulPronunciationStatus(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text, aiOverlay)
        ?.pronunciationStatus

    fun hasTranslation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasTranslation } == true

    fun hasPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasPronunciation } == true

    fun hasHangulPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasHangulPronunciation } == true

    fun translationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasTranslation }?.translationSource

    fun translationMatchPercentage(songId: String?, source: String): Int? =
        overlay
            ?.takeIf { it.songId == songId }
            ?.translationMatchStats
            ?.get(source)
            ?.percentage

    fun pronunciationMatchPercentage(songId: String?, source: String): Int? =
        overlay
            ?.takeIf { it.songId == songId }
            ?.pronunciationMatchStats
            ?.get(source)
            ?.percentage

    fun pronunciationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }?.pronunciationSource

    fun translation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text)?.translation

    fun pronunciation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = content(songId, begin, end, text)?.pronunciation

    private fun content(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
        current: Overlay? = overlay,
    ): Content? {
        current ?: return null
        if (songId.isNullOrBlank() || current.songId != songId) return null

        val timing = TimingKey(begin, end)
        current.exactContent[LineKey(timing, normalizeText(text))]?.let { return it }
        // A shared timestamp is not proof that two lines have the same text.
        // Apple sometimes exposes different line subdivisions at one clock;
        // attaching the other line's meaning here put Korean sentences under
        // unrelated Japanese text. Match identity even on exact time.
        val identity = normalizedSourceIdentity(text)
        if (identity.isEmpty()) return null
        current.translationsByTiming[timing]?.singleOrNull()
            ?.takeIf { normalizedSourceIdentity(it.text) == identity }
            ?.content?.let { return it }

        // Native Apple timed lines occasionally differ slightly from the
        // standalone fetched lyric's begin/end, or punctuation/whitespace.
        // Never use a global text-only match: repeated choruses could receive
        // the wrong occurrence's Korean reading.
        if (begin < 0 || end <= begin) return null
        val candidates = current.exactContent.asSequence().mapNotNull { (key, value) ->
            if (normalizedSourceIdentity(key.text) != identity) return@mapNotNull null
            val beginDiff = kotlin.math.abs(key.timing.begin - begin)
            val endDiff = kotlin.math.abs(key.timing.end - end)
            if (beginDiff > 800L || endDiff > 800L) return@mapNotNull null
            (beginDiff + endDiff) to value
        }.sortedBy { it.first }.toList()
        val closest = candidates.firstOrNull() ?: return null
        val next = candidates.getOrNull(1)
        // Equal-content refrains can share one reading. Otherwise require
        // a clearly closer time anchor or refuse an ambiguous attachment.
        if (next != null && next.second != closest.second &&
            closest.first * 2L >= next.first
        ) return null
        return closest.second
    }

    companion object {
        private fun normalizedSourceIdentity(text: String?): String =
            normalizeText(text).lowercase(java.util.Locale.ROOT)
                .filter(Char::isLetterOrDigit)

        val WHITESPACE = Regex("\\s+")

        fun sanitizeTranslation(text: String?): String? =
            sanitizeContent(text)

        fun sanitizeContent(text: String?): String? =
            OnlineTranslationContentPolicy.sanitize(text)

        fun normalizeText(text: String?): String =
            text.orEmpty().replace(WHITESPACE, " ").trim()
    }
}
