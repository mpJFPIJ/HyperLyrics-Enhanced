/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLyricsActiveLineEditPolicyTest {
    private val lines = listOf(
        AppleLyricsActiveLineEditPolicy.Line(1_000, 2_000, "first"),
        AppleLyricsActiveLineEditPolicy.Line(2_000, 3_000, "second"),
        AppleLyricsActiveLineEditPolicy.Line(3_000, 4_000, "third"),
    )

    @Test
    fun `editor targets only the active timed lyric row`() {
        assertNull(AppleLyricsActiveLineEditPolicy.current(lines, 999))
        assertEquals("first", AppleLyricsActiveLineEditPolicy.current(lines, 1_000)?.text)
        assertEquals("first", AppleLyricsActiveLineEditPolicy.current(lines, 1_999)?.text)
        assertEquals("second", AppleLyricsActiveLineEditPolicy.current(lines, 2_000)?.text)
        assertNull(AppleLyricsActiveLineEditPolicy.current(lines, 4_000))
    }

    @Test
    fun `overlapping lyric rows resolve to the most recent valid start`() {
        val overlapping = listOf(
            AppleLyricsActiveLineEditPolicy.Line(1_000, 4_000, "first voice"),
            AppleLyricsActiveLineEditPolicy.Line(2_000, 3_000, "second voice"),
        )
        assertEquals("second voice", AppleLyricsActiveLineEditPolicy.current(overlapping, 2_500)?.text)
        assertEquals("first voice", AppleLyricsActiveLineEditPolicy.current(overlapping, 3_500)?.text)
    }

    @Test fun `equal-start original rows cannot authorize editing an arbitrary line`() {
        val tied = listOf(
            AppleLyricsActiveLineEditPolicy.Line(1_000, 3_000, "original A"),
            AppleLyricsActiveLineEditPolicy.Line(1_000, 2_500, "original B"),
        )
        assertNull(AppleLyricsActiveLineEditPolicy.current(tied, 1_500L))
        assertEquals("original A", AppleLyricsActiveLineEditPolicy.current(tied, 2_700L)?.text)
        val later = tied + AppleLyricsActiveLineEditPolicy.Line(2_000, 2_800, "new voice")
        assertEquals("new voice", AppleLyricsActiveLineEditPolicy.current(later, 2_200L)?.text)
        assertNull(AppleLyricsActiveLineEditPolicy.current(tied.reversed(), 1_500L))
    }

    @Test
    fun `empty and invalid timing never choose an unrelated lyric row`() {
        val invalid = listOf(
            AppleLyricsActiveLineEditPolicy.Line(-1, 1_000, "negative"),
            AppleLyricsActiveLineEditPolicy.Line(1_000, 1_000, "zero length"),
            AppleLyricsActiveLineEditPolicy.Line(2_000, 1_000, "reversed"),
            AppleLyricsActiveLineEditPolicy.Line(1_000, 2_000, " "),
        )
        assertNull(AppleLyricsActiveLineEditPolicy.current(invalid, -1))
        assertNull(AppleLyricsActiveLineEditPolicy.current(invalid, 1_500))
        assertNull(AppleLyricsActiveLineEditPolicy.current(emptyList(), 1_500))
    }
}
