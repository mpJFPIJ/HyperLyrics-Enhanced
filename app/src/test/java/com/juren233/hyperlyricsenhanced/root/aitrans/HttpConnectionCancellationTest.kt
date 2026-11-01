package com.juren233.hyperlyricsenhanced.root.aitrans

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConnectionCancellationTest {
    @Test fun cancelingParentDisconnectsBlockedHttpRequest() = runBlocking {
        val disconnected = AtomicBoolean(false)
        val ready = CompletableDeferred<Unit>()
        val connection = object : HttpURLConnection(URL("https://example.com")) {
            override fun connect() = Unit
            override fun disconnect() { disconnected.set(true) }
            override fun usingProxy(): Boolean = false
        }
        val worker = launch(Dispatchers.IO) {
            val watcher = HttpConnectionCancellation.watch(connection)
            ready.complete(Unit)
            try { awaitCancellation() } finally { watcher.cancel() }
        }
        ready.await()
        worker.cancelAndJoin()
        assertTrue(disconnected.get())
    }
}
