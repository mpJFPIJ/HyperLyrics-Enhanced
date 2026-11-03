/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** A read-only row identity for the editor; not a mutation of Apple's TTML model. */
internal object AppleLyricsActiveLineEditPolicy {
    data class Line(val begin: Long, val end: Long, val text: String)

    /** Never fall back to an unrelated line during gaps, before the song or after its end. */
    fun current(lines: List<Line>, playbackPositionMs: Long): Line? {
        if (playbackPositionMs < 0L) return null
        return lines.asSequence()
            .filter { line ->
                line.begin >= 0L && line.end > line.begin &&
                    line.begin <= playbackPositionMs && playbackPositionMs < line.end &&
                    line.text.isNotBlank()
            }
            .maxByOrNull { it.begin }
    }
}
