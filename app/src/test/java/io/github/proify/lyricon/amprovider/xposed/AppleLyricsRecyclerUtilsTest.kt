/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsRecyclerUtilsTest {
    private open class BaseHolder {
        var lineText: String? = "line"
        var translation: String? = "translation"
        var lineId: Int = 3
        var songId: String? = "song-42"
        val immutableLabel = "keep"
    }

    private class Holder : BaseHolder() {
        var pronunciation: String? = "roma"
        val payloads = LinkedHashSet<String>()
        var selected: Boolean = true
    }

    private class ImmutableHolder(val lineText: String, var opaqueId: String)

    @Test
    fun `unknown and immutable fields are left to the host`() {
        val holder = ImmutableHolder("original", "opaque")
        assertEquals(0, clearAppleLyricsHolderTextCaches(holder))
        assertEquals("original", holder.lineText)
        assertEquals("opaque", holder.opaqueId)
    }

    @Test
    fun `clears known mutable text caches across hierarchy but keeps other fields`() {
        val holder = Holder()

        assertEquals(3, clearAppleLyricsHolderTextCaches(holder))

        assertNull(holder.lineText)
        assertNull(holder.translation)
        assertNull(holder.pronunciation)
        assertEquals("song-42", holder.songId)
        assertEquals("keep", holder.immutableLabel)
        assertEquals(3, holder.lineId)
        assertTrue(holder.selected)
        assertEquals(0, holder.payloads.size)
    }
}
