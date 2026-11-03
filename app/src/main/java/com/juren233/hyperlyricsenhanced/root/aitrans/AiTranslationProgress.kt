package com.juren233.hyperlyricsenhanced.root.aitrans

/** Non-sensitive translation progress for the optional Apple Music status chip. */
data class AiTranslationProgress(
    val stage: String,
    val batch: Int = 0,
    val totalBatches: Int = 0,
    val fragments: Int = 0,
)
