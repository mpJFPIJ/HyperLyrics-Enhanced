package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleLyricsAdapterRefreshPolicyTest {
    private open class HostBaseAdapter {
        val notified = mutableListOf<Int>()
        @Suppress("unused") fun h(position: Int) { notified += position }
    }
    private class HostObfuscatedAdapter : HostBaseAdapter()

    private class StandardAdapter {
        val notified = mutableListOf<Int>()
        @Suppress("unused") fun notifyItemRangeChanged(start: Int, count: Int) {
            notified += (start until start + count).toList()
        }
    }

    @Test fun hostObfuscatedRecyclerCanRebindVisibleLyricsUsingProfiledNotification() {
        val adapter = HostObfuscatedAdapter()
        val profiled = HostBaseAdapter::class.java.getDeclaredMethod(
            "h", Int::class.javaPrimitiveType,
        )
        assertEquals(3, AppleLyricsAdapterRefreshPolicy.notifyRange(
            adapter, 4..6, profiled,
        ))
        assertEquals(listOf(4, 5, 6), adapter.notified)
    }

    @Test fun unobfuscatedHostKeepsItsNormalRangeNotification() {
        val adapter = StandardAdapter()
        assertEquals(2, AppleLyricsAdapterRefreshPolicy.notifyRange(
            adapter, 2..3, null,
        ))
        assertEquals(listOf(2, 3), adapter.notified)
    }

    @Test fun missingHostNotificationDoesNotPretendLyricsWereRebound() {
        assertThrows(NoSuchMethodException::class.java) {
            AppleLyricsAdapterRefreshPolicy.notifyRange(Any(), 1..2, null)
        }
    }
}
