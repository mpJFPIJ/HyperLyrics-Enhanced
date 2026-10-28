package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiWebReferencePolicyTest {
    private val base = AiTranslationConfigs(
        provider = "OPENAI", targetLanguage = "ko", model = "gemini-2.5-flash",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiKey = "private", webSearchEnabled = true,
    )

    @Test
    fun `web grounding uses free tier 2_5 Flash without downgrading final translation`() {
        val translator = base.copy(model = "gemini-3.8-flash")
        assertEquals(
            "gemini-2.5-flash",
            GeminiWebReferencePolicy.researchModelFor(translator, "君の名は", "RADWIMPS"),
        )
        assertEquals("gemini-3.8-flash", translator.model)
    }

    @Test
    fun `free web grounding refuses disabled non Korean and unofficial translators`() {
        val translator = base.copy(model = "gemini-3.8-flash")
        assertNull(GeminiWebReferencePolicy.researchModelFor(
            translator.copy(webSearchEnabled = false), "君の名は", "RADWIMPS",
        ))
        assertNull(GeminiWebReferencePolicy.researchModelFor(
            translator.copy(targetLanguage = "en"), "君の名は", "RADWIMPS",
        ))
        assertNull(GeminiWebReferencePolicy.researchModelFor(
            translator.copy(baseUrl = "https://proxy.example/v1"), "君の名は", "RADWIMPS",
        ))
    }

    @Test
    fun `only opted-in official Gemini endpoints can search`() {
        assertTrue(GeminiWebReferencePolicy.canSearch(base, "君の名は", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base.copy(webSearchEnabled = false), "君の名は", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base.copy(baseUrl = "https://evil.example/v1"), "君の名は", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base.copy(model = "gpt-4o"), "君の名は", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base.copy(targetLanguage = "中文"), "君の名は", "RADWIMPS"))
        assertTrue(GeminiWebReferencePolicy.canSearch(base.copy(targetLanguage = "한국어"), "君の名は", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base, "Unknown Track", "RADWIMPS"))
        assertFalse(GeminiWebReferencePolicy.canSearch(base, "君の名は", "Unknown Artist"))
    }

    @Test
    fun `requires actual search and a cited model output before using web reference`() {
        val response = """{
            "steps": [
                {"type":"google_search_call","arguments":{"queries":["RADWIMPS 君の名は 韓国語 翻訳"]}},
                {"type":"google_search_result","result":[{"search_suggestions":"available"}]},
                {"type":"model_output","content":[{"type":"text","text":"Theme: reunion and distance. Use natural Korean expressions.","annotations":[{"type":"url_citation","url":"https://example.com"}]}]}
            ]
        }""".trimIndent()
        assertEquals(
            "Theme: reunion and distance. Use natural Korean expressions.",
            GeminiWebReferencePolicy.parseGroundedReference(response),
        )
        assertNull(GeminiWebReferencePolicy.parseGroundedReference(
            """{"steps":[{"type":"model_output","content":[{"type":"text","text":"Unverified guess"}]}]}"""
        ))
        assertNull(GeminiWebReferencePolicy.parseGroundedReference(
            response.replace("\"annotations\"", "\"unverified\""),
        ))
    }

    @Test
    fun `search rejection and oversized references cannot be injected into translator`() {
        assertNull(GeminiWebReferencePolicy.parseGroundedReference("{}"))
        assertNull(GeminiWebReferencePolicy.parseGroundedReference("<html>error</html>"))
        assertNull(GeminiWebReferencePolicy.parseGroundedReference("""{"steps":[{"type":{}}]}"""))
        val huge = "word".repeat(1_000)
        val response = """{"steps":[{"type":"google_search_call"},{"type":"google_search_result"},{"type":"model_output","content":[{"type":"text","text":"$huge","annotations":[{"type":"url_citation","url":"https://example.com"}]}]}]}"""
        assertNull(GeminiWebReferencePolicy.parseGroundedReference(response))
    }

    @Test
    fun `Gemini 2_5 native grounding requires source metadata not just invented model text`() {
        val grounded = """{"candidates":[{"content":{"parts":[{"text":"가사 속 이별은 재회를 암시하는 시적 표현."}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/lyrics"}}]}}]}"""
        assertEquals("가사 속 이별은 재회를 암시하는 시적 표현.",
            GeminiWebReferencePolicy.parseGroundedReference(grounded))
        assertNull(GeminiWebReferencePolicy.parseGroundedReference(
            grounded.replace("groundingChunks", "unverified"),
        ))
    }
}
