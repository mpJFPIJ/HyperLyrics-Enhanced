package com.juren233.hyperlyricsenhanced.lyric.model

import kotlinx.serialization.Serializable

/** Source offsets allow the Apple adapter to re-check an alignment after transport. */
@Serializable
data class LyricPronunciationSpan(
    val start: Int,
    val end: Int,
    val source: String,
    val hangul: String,
)
