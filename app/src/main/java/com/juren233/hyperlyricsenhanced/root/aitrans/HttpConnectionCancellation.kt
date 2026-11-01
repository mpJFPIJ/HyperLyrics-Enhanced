package com.juren233.hyperlyricsenhanced.root.aitrans

import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** A cancelled song disconnects an otherwise blocking HttpURLConnection read immediately. */
internal object HttpConnectionCancellation {
    suspend fun watch(connection: HttpURLConnection): Job =
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
}
