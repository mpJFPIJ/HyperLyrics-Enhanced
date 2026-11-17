package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class AppleNativeTranslationHighlightPolicyTest {
    @Test fun repeatedViewBindingRetainsTheSameTranslationClockUntilHolderRecycles() {
        val holder = Any()
        val context = AppleTranslationLineContext("song", "駄目", 1000, 1900, "안 돼", 2)
        val store = AppleTranslationLineContextStore()
        store.remember(holder, context)
        assertSame(context, store.forHolder(holder))
        assertSame(context, store.forHolder(holder))
        store.clear(holder)
        assertNull(store.forHolder(holder))
    }

    @Test fun recycledTranslationViewCannotRestoreOrRecolorAnUnrelatedLyric() {
        val controlledText: CharSequence = StringBuilder("안 돼")
        assertTrue(AppleNativeTranslationHighlightPolicy.isStillBound(
            controlledText, controlledText,
        ))
        assertFalse(AppleNativeTranslationHighlightPolicy.isStillBound(
            controlledText, StringBuilder("다음 가사"),
        ))
    }
    @Test fun noSourceWordOrReadingViewMayBeMistakenForTranslatedMeaning() {
        val meaning = "머릿속에서 그만둬 라고 외치는 도덕성이"
        assertTrue(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "lyrics_translation_line", meaning, meaning))
        assertFalse(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "song_lyrics_word", meaning, meaning))
        assertFalse(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            "lyrics_pronunciation_vertical_container", meaning, meaning))
        assertTrue(AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell(
            null, meaning, meaning))
    }

    @Test fun wrappedKoreanTranslationMustHaveOneNativeLineActiveWindow() {
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(999L, 1000L, 2000L))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(1000L, 1000L, 2000L))
        assertTrue(AppleNativeTranslationHighlightPolicy.lineActive(1999L, 1000L, 2000L))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(2000L, 1000L, 2000L))
        assertFalse(AppleNativeTranslationHighlightPolicy.lineActive(null, 1000L, 2000L))
    }

    @Test fun eachPhysicalLineOfLongTranslationMatchesSameLogicalLine() {
        val full = "안 돼, 안 돼, 안 돼. 머릿속에서 그만둬, 바보야 라고 외치는 도덕성이"
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "안 돼, 안 돼, 안 돼"))
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "머릿속에서"))
        assertTrue(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "외치는 도덕성이"))
        assertFalse(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "미소노 나카카라"))
        assertFalse(AppleNativeTranslationHighlightPolicy.fragmentMatches(full, "の"))
    }
}
