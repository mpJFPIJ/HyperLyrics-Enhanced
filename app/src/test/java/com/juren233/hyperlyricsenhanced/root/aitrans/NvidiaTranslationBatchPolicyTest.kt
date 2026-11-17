package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaTranslationBatchPolicyTest {
    @Test fun largeSongSplitsWithoutChangingOriginalLineIndices() {
        val original = (0 until 70).map { TranslationRequestItem(it * 2, "line $it") }
        val batches = NvidiaTranslationBatchPolicy.batches(original)

        assertEquals(listOf(15, 15, 15, 15, 10), batches.map { it.size })
        assertEquals(original, batches.flatten())
    }

    @Test fun smallOrEmptySongNeedsAtMostOneRequest() {
        assertTrue(NvidiaTranslationBatchPolicy.batches(emptyList()).isEmpty())
        val short = (0 until 5).map { TranslationRequestItem(it, "line $it") }
        assertEquals(listOf(short), NvidiaTranslationBatchPolicy.batches(short))
    }

    @Test fun batchDeadlineCannotOutlastOverallSongDeadline() {
        val second = 1_000_000_000L
        assertEquals(75 * second, NvidiaTranslationBatchPolicy.deadlineNanos(0, 0))
        assertEquals(180 * second, NvidiaTranslationBatchPolicy.deadlineNanos(0, 150 * second))
        assertEquals(180 * second, NvidiaTranslationBatchPolicy.deadlineNanos(0, 181 * second))
    }

    @Test fun incompleteOrDuplicatedBatchCannotBeAcceptedAsAFullSong() {
        val requested = listOf(TranslationRequestItem(4, "hello"), TranslationRequestItem(9, "world"))
        assertTrue(NvidiaTranslationBatchPolicy.isComplete(requested, listOf(
            TranslationItem(4, "안녕"), TranslationItem(9, "세상"),
        )))
        assertTrue(!NvidiaTranslationBatchPolicy.isComplete(requested, listOf(
            TranslationItem(4, "안녕"),
        )))
        assertTrue(!NvidiaTranslationBatchPolicy.isComplete(requested, listOf(
            TranslationItem(4, "안녕"), TranslationItem(4, "중복"),
        )))
        assertTrue(!NvidiaTranslationBatchPolicy.isComplete(requested, listOf(
            TranslationItem(4, "안녕"), TranslationItem(9, ""),
        )))
    }

    @Test fun multipleNvidiaRequestsRotateExistingApiKeyPool() {
        assertEquals(0, NvidiaTranslationBatchPolicy.keySlot(0, 0, 2))
        assertEquals(1, NvidiaTranslationBatchPolicy.keySlot(1, 0, 2))
        assertEquals(0, NvidiaTranslationBatchPolicy.keySlot(2, 0, 2))
        assertEquals(1, NvidiaTranslationBatchPolicy.keySlot(0, 1, 2))
    }
}
