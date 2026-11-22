package com.juren233.hyperlyricsenhanced.service

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricMetadata
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song

/** Pure validation rules shared by the NPatch fallback service and unit tests. */
internal object AppleMusicAiTranslationPolicy {
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

    fun isAuthorizedCaller(packagesForUid: Array<String>?): Boolean =
        packagesForUid?.contains(APPLE_MUSIC_PACKAGE) == true

    fun shouldTranslate(
        enabled: Boolean,
        hasLyrics: Boolean,
        hasMissingTranslation: Boolean,
        skipExisting: Boolean,
        forceOverride: Boolean,
        hasPronunciationWork: Boolean = false,
    ): Boolean =
        enabled && hasLyrics &&
            (hasMissingTranslation || hasPronunciationWork || !skipExisting || forceOverride)

    /** Only results computed for this source may qualify as generated translation or phonetics. */
    fun sourceOnly(song: Song): Song = song.copy(lyrics = song.lyrics?.map { applyGeneratedFields(it) })

    /** Keep timing, native words and metadata from the latest source snapshot. */
    fun applyGeneratedFields(source: RichLyricLine, generated: RichLyricLine? = null): RichLyricLine =
        source.copy(
            translation = generated?.translation,
            translationWords = null,
            roma = generated?.roma,
            pronunciationWords = null,
            pronunciationSpans = null,
            pronunciationStatus = generated?.pronunciationStatus,
        )

    fun transportLine(source: RichLyricLine, generated: RichLyricLine, forceOverride: Boolean): RichLyricLine {
        val incoming = source.translation?.takeIf(String::isNotBlank)
        val computed = generated.translation?.takeIf(String::isNotBlank)
        val preserve = incoming != null && (computed == null || !forceOverride)
        val inherited = preserve && incoming != computed
        val inheritedKey = LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION
        val generatedKey = LyricMetadataKeys.STANDALONE_GENERATED_TRANSLATION
        val sourceMetadata = generated.metadata.orEmpty()
        val metadata = if (inherited || inheritedKey in sourceMetadata || generatedKey in sourceMetadata) {
            // Only the current computation can populate these private transport fields.
            val fields = sourceMetadata.toMutableMap()
            fields.remove(inheritedKey)
            fields.remove(generatedKey)
            if (inherited) {
                fields[inheritedKey] = "true"
                if (computed != null) fields[generatedKey] = computed
            }
            LyricMetadata(fields)
        } else generated.metadata
        return generated.copy(
            translation = if (preserve) incoming else computed,
            translationWords = if (preserve) source.translationWords else generated.translationWords,
            metadata = metadata,
        )
    }

    /** A complete phonetics-only result is useful even while translation is unavailable. */
    fun shouldDeliverResult(translatedLines: Int, pronouncedLines: Int): Boolean =
        translatedLines > 0 || pronouncedLines > 0
}
