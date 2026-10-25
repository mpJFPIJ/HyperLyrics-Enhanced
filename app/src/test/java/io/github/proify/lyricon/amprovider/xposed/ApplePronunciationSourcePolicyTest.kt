package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePronunciationSourcePolicyTest {
    @Test
    fun `default preserves Hangul overlay over Apple Latin romanization`() {
        val selection = ApplePronunciationSourcePolicy.select(
            official = "Kimi no na wa",
            supplement = "키미노 나와",
            priority = RootConstants.DEFAULT_HOOK_APPLE_MUSIC_PRONUNCIATION_SOURCE_PRIORITY,
        )
        assertEquals("키미노 나와", selection.text)
        assertTrue(selection.fromSupplement)
    }

    @Test
    fun `default preserves original Apple preference for non Hangul supplemental text`() {
        val selection = ApplePronunciationSourcePolicy.select(
            official = "Kimi no na wa",
            supplement = "kimi no na wa",
            priority = RootConstants.APPLE_MUSIC_PRONUNCIATION_HANGUL_FIRST,
        )
        assertEquals("Kimi no na wa", selection.text)
        assertFalse(selection.fromSupplement)
    }

    @Test
    fun `Apple first still uses supplement if official text is missing`() {
        assertEquals(
            ApplePronunciationSourcePolicy.Selection("키미노 나와", true),
            ApplePronunciationSourcePolicy.select(
                official = " ",
                supplement = "키미노 나와",
                priority = RootConstants.APPLE_MUSIC_PRONUNCIATION_APPLE_FIRST,
            ),
        )
    }

    @Test
    fun `supplement first chooses a non Hangul alternative when requested`() {
        assertEquals(
            ApplePronunciationSourcePolicy.Selection("kimi no na wa", true),
            ApplePronunciationSourcePolicy.select(
                official = "Kimi no na wa",
                supplement = "kimi no na wa",
                priority = RootConstants.APPLE_MUSIC_PRONUNCIATION_SUPPLEMENT_FIRST,
            ),
        )
    }

    @Test
    fun `Apple first chooses official and unknown values revert to default`() {
        assertEquals(
            ApplePronunciationSourcePolicy.Selection("Kimi no na wa", false),
            ApplePronunciationSourcePolicy.select(
                official = "Kimi no na wa",
                supplement = "키미노 나와",
                priority = RootConstants.APPLE_MUSIC_PRONUNCIATION_APPLE_FIRST,
            ),
        )
        assertEquals(
            ApplePronunciationSourcePolicy.Selection("키미노 나와", true),
            ApplePronunciationSourcePolicy.select(
                official = "Kimi no na wa",
                supplement = "키미노 나와",
                priority = -20,
            ),
        )
    }

    @Test
    fun `missing sources never invent a pronunciation`() {
        val selection = ApplePronunciationSourcePolicy.select(
            official = null,
            supplement = "",
            priority = RootConstants.APPLE_MUSIC_PRONUNCIATION_SUPPLEMENT_FIRST,
        )
        assertNull(selection.text)
        assertFalse(selection.fromSupplement)
    }
}
