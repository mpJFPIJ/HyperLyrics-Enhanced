package com.juren233.hyperlyricsenhanced.lyric.model

import kotlinx.serialization.Serializable

/** Optional word-aligned reading, never a replacement for original karaoke words/timing. */
@Serializable
data class LyricWordPronunciation(val source: String, val hangul: String)
