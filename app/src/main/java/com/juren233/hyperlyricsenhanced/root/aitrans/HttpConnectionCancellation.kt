package com.juren233.hyperlyricsenhanced.root.aitrans

import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A cancelled song disconnects an otherwise blocking HttpURLConnection read immediately. */
internal object HttpConnectionCancellation {
    suspend fun watch(connection: HttpURLConnection, deadlineNanos: Long? = null): Job =
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (deadlineNanos != null) {
                    val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
                    delay((remainingNanos / 1_000_000L).coerceAtLeast(1L))
                    // HttpURLConnection reads are blocking: a coroutine deadline alone
                    // cannot interrupt them. Closing the connection wakes the read.
                    connection.disconnect()
                }
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
}
