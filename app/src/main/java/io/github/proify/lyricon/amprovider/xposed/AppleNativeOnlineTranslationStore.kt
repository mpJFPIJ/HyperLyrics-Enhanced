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
        /** Unnormalized source: verified spans index this exact UTF-16 sequence. */
        val sourceText: String?,
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
        /** Conflicting records at the same raw source/clock remain negative
         * evidence; a later nearby match must not hide the ambiguity. */
        val conflictingLineKeys: Set<LineKey>,
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
            val identicalOffsetSource = exactPreviousContent != null &&
                exactPreviousContent.sourceText == content.sourceText
            val keepOldOffsets = keptPrevious && identicalOffsetSource
            // A later AI meaning refresh can return the *same* verified Hangul
            // with no source offsets. Do not demote an exact source row
            // from native-word reading to line-wide solely because the second
            // receipt omitted optional proof. Never transfer anchors from a
            // timing-only/normalized-identity match or across changed sounds.
            val keepExactAnchors = identicalOffsetSource &&
                content.pronunciationSpans.isNullOrEmpty() &&
                !exactPreviousContent?.pronunciationSpans.isNullOrEmpty() &&
                RomanizationPolicy.sameHangulSounds(
                    content.pronunciation, exactPreviousContent?.pronunciation,
                )
            content.copy(
                pronunciation = displayReading,
                // The old words and status belong to the same proven spans;
                // do not mix them with unanchored words in the later receipt.
                wordPronunciations = if (keepOldOffsets || keepExactAnchors)
                    exactPreviousContent?.wordPronunciations else content.wordPronunciations,
                pronunciationSpans = if (keepOldOffsets || keepExactAnchors)
                    exactPreviousContent?.pronunciationSpans else content.pronunciationSpans,
                pronunciationStatus = if (keepOldOffsets || keepExactAnchors)
                    exactPreviousContent?.pronunciationStatus ?: content.pronunciationStatus
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
                sourceText = line.text,
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
        val grouped = entries.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        )
        val conflictingKeys = grouped.filterValues { values ->
            values.drop(1).any { it != values.first() }
        }.keys
        return Overlay(
            songId = songId,
            exactContent = grouped.filterKeys { it !in conflictingKeys }
                .mapValues { (_, values) -> values.first() },
            conflictingLineKeys = conflictingKeys,
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
        // Hangul Jamo, digits and supplementary non-Hangul characters must
        // not disappear from a syllables-only comparison. UTF-16 Char checks
        // alone incorrectly ignore both halves of supplementary Han.
        fun codePointBoundary(offset: Int): Boolean =
            offset in 0..source.length && !(offset > 0 && offset < source.length &&
                Character.isHighSurrogate(source[offset - 1]) &&
                Character.isLowSurrogate(source[offset])) &&
                (offset == source.length || !appleIsJapaneseVoicingMark(
                    Character.codePointAt(source, offset),
                ))
        if (!appleOnlyHangulSoundLetters(chosen)) return null
        val expectedSound = chosen.filter { it in '가'..'힣' }
        if (expectedSound.isEmpty()) return null
        for (candidate in listOfNotNull(
            content(songId, begin, end, source, aiOverlay),
            content(songId, begin, end, source, overlay),
        )) {
            // A normalized punctuation/whitespace match can select different
            // raw UTF-16 source text. Never borrow its source offsets.
            if (candidate.sourceText != source ||
                !RomanizationPolicy.isHangulPronunciation(candidate.pronunciation) ||
                !appleOnlyHangulSoundLetters(candidate.pronunciation.orEmpty())
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
                    !codePointBoundary(span.start) || !codePointBoundary(span.end) ||
                    source.substring(span.start, span.end) != span.source
                ) {
                    valid = false
                    break
                }
                val syllables = span.hangul.filter { it in '가'..'힣' }
                if (syllables.isEmpty() || !appleOnlyHangulSoundLetters(span.hangul) ||
                    !appleHasSpokenSourceBase(span.source)
                ) {
                    valid = false
                    break
                }
                sound.append(syllables)
                tokenEnds += sound.length
                for (index in span.start until span.end) covered[index] = true
                cursor = span.end
            }
            // Char.isLetterOrDigit does not recognize a supplementary Han's
            // surrogate halves. Both UTF-16 units must belong to a verified
            // token before this can be a complete source-to-sound map.
            var uncoveredSource = false
            var offset = 0
            while (offset < source.length) {
                val cp = Character.codePointAt(source, offset)
                val end = offset + Character.charCount(cp)
                if (appleIsLyricSourceCodePoint(cp) &&
                    (offset until end).any { !covered[it] }
                ) {
                    uncoveredSource = true
                    break
                }
                offset = end
            }
            if (!valid || sound.toString() != expectedSound || uncoveredSource) continue
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

    /** A complete-row proof must not be inferred from partial dictionary spans.
     * Nevertheless, when another provider selects the exact same Hangul sound,
     * the already verified *partial* anchors can still be offered to the
     * native-word projector. That projector independently refuses a sound gap
     * crossing two native clocks. This method grants no complete-row proof. */
    fun soundMatchedPartialPronunciationSpans(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
        selectedPronunciation: String?,
    ): List<LyricPronunciationSpan>? {
        val source = text?.takeIf(String::isNotBlank) ?: return null
        val selected = selectedPronunciation
            ?.takeIf(RomanizationPolicy::isHangulPronunciation) ?: return null
        if (!appleOnlyHangulSoundLetters(selected)) return null
        val chosenSound = selected.filter { it in '가'..'힣' }
        if (chosenSound.isEmpty()) return null
        fun codePointBoundary(offset: Int): Boolean =
            offset in 0..source.length && !(offset > 0 && offset < source.length &&
                Character.isHighSurrogate(source[offset - 1]) &&
                Character.isLowSurrogate(source[offset])) &&
                (offset == source.length || !appleIsJapaneseVoicingMark(
                    Character.codePointAt(source, offset),
                ))

        for (candidate in listOfNotNull(
            content(songId, begin, end, source, aiOverlay),
            content(songId, begin, end, source, overlay),
        )) {
            // The existing normalized/timing lookup may choose a differently
            // spaced original. Its UTF-16 offsets are NOT transferable.
            if (candidate.sourceText != source ||
                !RomanizationPolicy.isHangulPronunciation(candidate.pronunciation) ||
                !appleOnlyHangulSoundLetters(candidate.pronunciation.orEmpty()) ||
                candidate.pronunciation.orEmpty().filter { it in '가'..'힣' } != chosenSound
            ) continue
            val spans = candidate.pronunciationSpans.orEmpty()
            if (spans.isEmpty()) continue
            val covered = BooleanArray(source.length)
            var sourceCursor = 0
            var soundCursor = 0
            var valid = true
            for (span in spans) {
                if (span.start < sourceCursor || span.end <= span.start ||
                    !codePointBoundary(span.start) || !codePointBoundary(span.end) ||
                    source.substring(span.start, span.end) != span.source ||
                    !appleHasSpokenSourceBase(span.source) ||
                    !appleOnlyHangulSoundLetters(span.hangul)
                ) {
                    valid = false
                    break
                }
                val tokenSound = span.hangul.filter { it in '가'..'힣' }
                val soundStart = chosenSound.indexOf(tokenSound, soundCursor)
                if (tokenSound.isEmpty() || soundStart < 0 ||
                    (span.start == 0 && soundStart != 0) ||
                    (span.end == source.length && soundStart + tokenSound.length != chosenSound.length) ||
                    (soundStart > soundCursor &&
                        chosenSound.indexOf(tokenSound, soundStart + 1) >= 0)
                ) {
                    valid = false
                    break
                }
                for (index in span.start until span.end) covered[index] = true
                sourceCursor = span.end
                soundCursor = soundStart + tokenSound.length
            }
            if (!valid) continue
            // Complete maps belong exclusively to soundMatchedPronunciationSpans,
            // whose stricter spacing proof cannot be bypassed by this path.
            var offset = 0
            var hasUncoveredSource = false
            var unownedVoicingMark = false
            while (offset < source.length) {
                val cp = Character.codePointAt(source, offset)
                val next = offset + Character.charCount(cp)
                if (Character.isLetterOrDigit(cp) &&
                    (offset until next).any { !covered[it] }
                ) hasUncoveredSource = true
                if (appleIsJapaneseVoicingMark(cp) && !covered[offset])
                    unownedVoicingMark = true
                offset = next
            }
            if (unownedVoicingMark) continue
            if (hasUncoveredSource) return spans
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
        val exactKey = LineKey(timing, normalizeText(text))
        if (exactKey in current.conflictingLineKeys) return null
        current.exactContent[exactKey]?.let { return it }
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
        fun distance(key: LineKey): Long? {
            if (normalizedSourceIdentity(key.text) != identity) return null
            val beginDiff = kotlin.math.abs(key.timing.begin - begin)
            val endDiff = kotlin.math.abs(key.timing.end - end)
            if (beginDiff > 800L || endDiff > 800L) return null
            return beginDiff + endDiff
        }
        val exactCandidates: Sequence<Pair<Long, Content?>> =
            current.exactContent.asSequence().mapNotNull { (key, value) ->
                distance(key)?.let { it to value }
            }
        val conflictingCandidates: Sequence<Pair<Long, Content?>> =
            current.conflictingLineKeys.asSequence().mapNotNull { key ->
                distance(key)?.let { it to null }
            }
        val candidates = (exactCandidates + conflictingCandidates)
            .sortedBy { it.first }.toList()
        val closest = candidates.firstOrNull() ?: return null
        if (closest.second == null) return null
        // Equal-content refrains can share one reading. Otherwise require
        // a clearly closer time anchor than EVERY conflicting candidate.
        // Two agreeing duplicates cannot conceal a third nearby meaning.
        for (index in 1 until candidates.size) {
            val (score, content) = candidates[index]
            if (content != closest.second && closest.first * 2L >= score) return null
        }
        return closest.second
    }

    companion object {
        private fun normalizedSourceIdentity(text: String?): String =
            appleLyricSourceKey(normalizeText(text).lowercase(java.util.Locale.ROOT))

        val WHITESPACE = Regex("\\s+")

        fun sanitizeTranslation(text: String?): String? =
            sanitizeContent(text)

        fun sanitizeContent(text: String?): String? =
            OnlineTranslationContentPolicy.sanitize(text)

        fun normalizeText(text: String?): String =
            text.orEmpty().replace(WHITESPACE, " ").trim()
    }
}
