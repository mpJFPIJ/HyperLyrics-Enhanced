package com.juren233.hyperlyricsenhanced.root.source

/** A UI preference may republish only the actual snapshot produced by that generation. */
internal class AppleAcceptedOnlinePublication {
    private var published: Any? = null
    private var originGeneration: Int? = null

    fun remember(song: Any?, generation: Int) {
        published = song
        originGeneration = if (song == null) null else generation
    }

    fun generationFor(song: Any?, currentGeneration: Int): Int? =
        originGeneration?.takeIf {
            song != null && published === song && it == currentGeneration
        }
}
