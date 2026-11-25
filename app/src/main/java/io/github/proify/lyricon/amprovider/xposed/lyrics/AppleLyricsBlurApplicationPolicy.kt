package io.github.proify.lyricon.amprovider.xposed

import kotlin.math.abs

/**
 * Avoid redundant RenderEffect allocations and animator resets when native
 * Apple lyric RecyclerView binds or measures an already-blurred child.
 */
internal object AppleLyricsBlurApplicationPolicy {
    fun canReuseExistingEffect(
        alreadyBlurred: Boolean,
        currentMode: Int?,
        requestedMode: Int,
        currentRadius: Float,
        targetRadius: Float,
        animatorRunning: Boolean,
    ): Boolean = alreadyBlurred &&
        !animatorRunning && currentMode == requestedMode &&
        abs(currentRadius - targetRadius) <= 0.01f
}
