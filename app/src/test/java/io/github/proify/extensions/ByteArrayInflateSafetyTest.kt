package io.github.proify.extensions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ByteArrayInflateSafetyTest {
    @Test fun validCompressedDataRoundTrips() {
        val payload = "가사 데이터".toByteArray(Charsets.UTF_8)
        assertArrayEquals(payload, payload.deflate().inflate())
    }

    @Test fun oversizedCompressedInputCannotExhaustTheReceiverMemory() {
        val huge = ByteArray(16 * 1024 * 1024 + 1) { 65 }
        assertThrows(IllegalArgumentException::class.java) { huge.deflate().inflate() }
    }

    @Test fun truncatedCompressedPayloadIsNotAcceptedAsPartialLyrics() {
        val payload = ByteArray(4096) { it.toByte() }.deflate()
        assertThrows(java.util.zip.DataFormatException::class.java) {
            payload.copyOf(payload.size - 4).inflate()
        }
    }
}
