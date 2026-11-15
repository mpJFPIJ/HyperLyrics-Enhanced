package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryPronunciationClientTest {
    @Test fun eachRecoveredIndexMustMatchTheJapaneseOriginal() {
        val payload = TranslationRequest(listOf(
            TranslationRequestItem(1, "君の名は"),
            TranslationRequestItem(2, "ありがとう"),
            TranslationRequestItem(3, "사랑해"),
        ))
        val accepted = PrimaryPronunciationClient.accepted(payload, listOf(
            TranslationItem(1, "너의 이름은", "키미노 나와"),
            TranslationItem(2, "고마워", "arigato"),
            TranslationItem(3, "사랑해", "사랑해"),
            TranslationItem(99, "", "가짜 발음"),
        ))
        assertEquals(listOf(1), accepted.map { it.index })
        assertEquals("", accepted.single().trans)
        assertEquals("키미노 나와", accepted.single().roma)
    }
}
