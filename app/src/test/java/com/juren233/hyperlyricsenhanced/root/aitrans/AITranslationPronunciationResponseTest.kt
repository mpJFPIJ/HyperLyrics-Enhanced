package com.juren233.hyperlyricsenhanced.root.aitrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AITranslationPronunciationResponseTest {
    @Test fun acceptsPronunciationAndKeepsOriginalIndex() {
        val content = """{"translations":[
            {"index":0,"trans":"너의 이름은","roma":"kimi no na wa"},
            {"index":2,"trans":"사랑해","roma":"아이 러브 유"},
            {"index":99,"trans":"wrong","roma":"wrong"}
        ]}"""
        val items = AITranslationResponseParser.parse(content, setOf(0, 2))
        assertEquals(listOf(0, 2), items.map { it.index })
        assertEquals("kimi no na wa", items[0].roma)
        assertEquals("아이 러브 유", items[1].roma)
    }

    @Test fun acceptsLegacyTranslationAndPronunciationOnlyItems() {
        val content = """{"translations":[
            {"index":0,"trans":"고마워"},
            {"index":1,"roma":"arigatou"},
            {"index":2,"trans":"","roma":"  "}
        ]}"""
        val items = AITranslationResponseParser.parse(content, setOf(0, 1, 2))
        assertEquals(2, items.size)
        assertNull(items[0].roma)
        assertEquals("", items[1].trans)
        assertEquals("arigatou", items[1].roma)
    }
}
