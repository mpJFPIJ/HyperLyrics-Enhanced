/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * R5 守卫：Blur 运行时状态由 AppleLyricsBlurState 自持，行为门面（Hooks）只保留
 * Hook 发现/安装/事件转交与一个状态组件引用；Engine/RecyclerUtils 不再越文件写门面字段。
 * 同时固定“七个重复常量只保留一份”的处置。
 */
class AppleLyricsBlurStateTest {
    @Test fun `long lyric only evicts oldest bound window instead of erasing all clock proof`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        for (row in 0..513) {
            val start = row * 1_000L
            index.remember("long-song", 2L, adapter, row, start, start + 900L)
        }
        // A 514th native row should evict a bounded oldest owner but retain
        // the 512 most recently *actually bound* rows in the same adapter.
        assertNull(index.focus("long-song", 2L, adapter, setOf(0), 500L))
        assertNull(index.focus("long-song", 2L, adapter, setOf(1), 1_500L))
        assertEquals(2, index.focus("long-song", 2L, adapter, setOf(2), 2_500L))
        assertEquals(512, index.focus("long-song", 2L, adapter, setOf(512), 512_500L))
        assertEquals(513, index.focus("long-song", 2L, adapter, setOf(513), 513_500L))
    }

    @Test fun `native holder rebind refreshes bounded window recency`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        for (row in 0..511) {
            val start = row * 1_000L
            index.remember("repeat", 3L, adapter, row, start, start + 900L)
        }
        index.remember("repeat", 3L, adapter, 0, 800_000L, 801_000L)
        index.remember("repeat", 3L, adapter, 512, 812_000L, 813_000L)
        assertNull(index.focus("repeat", 3L, adapter, setOf(1), 1_500L))
        assertEquals(0, index.focus("repeat", 3L, adapter, setOf(0), 800_500L))
        assertEquals(512, index.focus("repeat", 3L, adapter, setOf(512), 812_500L))
    }

    @Test fun `equal start native clock rows cannot choose a follow target by adapter order`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        index.remember("iris", 9L, adapter, 10, beginMs = 1_000L, endMs = 3_000L)
        index.remember("iris", 9L, adapter, 11, beginMs = 1_000L, endMs = 2_500L)

        assertNull(index.focus("iris", 9L, adapter, setOf(10, 11), 1_500L))
        assertEquals(10, index.focus("iris", 9L, adapter, setOf(10), 1_500L))
        assertEquals(11, index.focus("iris", 9L, adapter, setOf(11), 1_500L))
        // Once one real source clock expires, that row cannot veto the one
        // remaining active clock just because their starts used to tie.
        assertEquals(10, index.focus("iris", 9L, adapter, setOf(10, 11), 2_700L))

        index.remember("iris", 9L, adapter, 12, beginMs = 2_000L, endMs = 2_900L)
        assertEquals(12, index.focus("iris", 9L, adapter, setOf(10, 11, 12), 2_200L))
        assertNull(index.focus("iris", 9L, Any(), setOf(10, 11), 1_500L))
    }

    @Test fun `native clock capture belongs to a bound adapter even when meaning is disabled`() {
        val adapter = Any()
        fun binding(position: Int?, translationEnabled: Boolean?, owner: Any?,
                    revision: Long = 4L) =
            AppleLyricsBindingDiagnosticContext(
                songId = "iris", adapterClass = "LyricsAdapter", adapterIdentity = 42,
                methodName = "onBind", holder = Any(), position = position,
                translationEnabled = translationEnabled, pronunciationEnabled = true,
                adapterInstance = owner,
                presentationRevision = revision,
            )
        assertTrue(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = false, owner = adapter), "iris", 4L,
        ))
        assertTrue(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = true, owner = adapter), "iris", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(null, "iris", 4L))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = false, owner = null), "iris", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(null, translationEnabled = false, owner = adapter), "iris", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(-1, translationEnabled = false, owner = adapter), "iris", 4L,
        ))
        // A delayed getter from a recycled previous-song holder must not
        // insert its old source time under the newly presented song's index.
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = false, owner = adapter), "next-song", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = false, owner = adapter), null, 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.canObserveClock(
            binding(7, translationEnabled = false, owner = adapter), "iris", 5L,
        ))
    }

    @Test fun `native clock getter cannot publish across a reentrant song revision change`() {
        assertTrue(AppleLyricsNativeWindowBindingPolicy.presentationUnchanged(
            "iris", 4L, "iris", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.presentationUnchanged(
            "iris", 4L, "iris", 5L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.presentationUnchanged(
            "iris", 4L, "next", 4L,
        ))
        assertFalse(AppleLyricsNativeWindowBindingPolicy.presentationUnchanged(
            null, 4L, null, 4L,
        ))
    }

    @Test fun `adapter focus uses observed native window not mismatched logical index`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        index.remember("iris", 4, adapter, adapterPosition = 5,
            beginMs = 1_000L, endMs = 2_100L)
        index.remember("iris", 4, adapter, adapterPosition = 6,
            beginMs = 2_000L, endMs = 2_900L)
        assertEquals(5, index.focus("iris", 4, adapter, setOf(5, 6), 1_950L))
        assertEquals(6, index.focus("iris", 4, adapter, setOf(5, 6), 2_200L))
        assertEquals(true, index.matchesKnownWindow("iris", 4, adapter, 6, 2_200L))
        assertEquals(false, index.matchesKnownWindow("iris", 4, adapter, 5, 2_200L))
        assertNull(index.matchesKnownWindow("iris", 4, adapter, 8, 2_200L))
        assertNull(index.matchesKnownWindow("other", 4, adapter, 6, 2_200L))
        assertNull(index.focus("iris", 4, adapter, setOf(5, 6), 900L))
        assertNull(index.focus("iris", 4, adapter, emptySet(), 2_200L))
        // Even if the logical lyrics list has six lines, a host adapter row 6
        // is safe only because its real native binding established that clock.
        assertEquals(6, index.focus("iris", 4, adapter, setOf(6), 2_200L))
        assertNull(index.focus("another-song", 4, adapter, setOf(5, 6), 2_200L))
        index.remember("iris", 5, adapter, adapterPosition = 6,
            beginMs = 5_000L, endMs = 6_000L)
        assertNull(index.focus("iris", 5, adapter, setOf(5, 6), 2_200L))
        assertEquals(6, index.focus("iris", 5, adapter, setOf(5, 6), 5_200L))
    }

    @Test fun `same row numbers in two native adapters cannot borrow each others clocks`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val leftAdapter = Any()
        val rightAdapter = Any()
        index.remember("iris", 4, leftAdapter, 6, beginMs = 2_000L, endMs = 3_000L)

        // Even with the same current song/revision and native active row 6,
        // the other RecyclerView has never bound this timing occurrence.
        assertNull(index.focus("iris", 4, rightAdapter, setOf(6), 2_200L))
        assertNull(index.matchesKnownWindow("iris", 4, rightAdapter, 6, 2_200L))
        index.remember("iris", 4, rightAdapter, 6,
            beginMs = 5_000L, endMs = 6_000L)
        assertEquals(6, index.focus("iris", 4, leftAdapter, setOf(6), 2_200L))
        assertNull(index.focus("iris", 4, rightAdapter, setOf(6), 2_200L))
        assertEquals(6, index.focus("iris", 4, rightAdapter, setOf(6), 5_200L))
        assertNull(index.focus("iris", 4, leftAdapter, setOf(6), 5_200L))
        assertNull(index.focus("iris", 4, null, setOf(6), 2_200L))

        // A new source revision must invalidate both adapters together.
        index.remember("iris", 5, rightAdapter, 6,
            beginMs = 8_000L, endMs = 9_000L)
        assertNull(index.focus("iris", 5, leftAdapter, setOf(6), 2_200L))
        assertEquals(6, index.focus("iris", 5, rightAdapter, setOf(6), 8_200L))
    }

    @Test fun `unbound future revision cannot erase verified current adapter clocks`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        index.remember("iris", 4L, adapter, 17, 1_000L, 2_000L)
        // Host may probe next-song/revision native data outside any adapter
        // bind. Without an owner it cannot revise the current time axis.
        index.remember("next", 5L, null, 17, 10_000L, 11_000L)
        assertEquals(17, index.focus("iris", 4L, adapter, setOf(17), 1_500L))
        index.remember("next", 5L, adapter, -1, 10_000L, 11_000L)
        assertEquals(17, index.focus("iris", 4L, adapter, setOf(17), 1_500L))

        index.remember("next", 5L, adapter, 18, 10_000L, 11_000L)
        assertNull(index.focus("iris", 5L, adapter, setOf(17), 1_500L))
        assertEquals(18, index.focus("next", 5L, adapter, setOf(18), 10_500L))
    }

    @Test fun `old native adapter windows cannot focus or veto a new source revision`() {
        val index = AppleLyricsTimedAdapterWindowIndex()
        val adapter = Any()
        index.remember("iris", 4L, adapter, 6, 1_000L, 2_000L)

        assertEquals(6, index.focus("iris", 4L, adapter, setOf(6), 1_500L))
        assertEquals(true, index.matchesKnownWindow("iris", 4L, adapter, 6, 1_500L))
        // Same song, same adapter and row: a newly presented lyrics revision
        // must not inherit old source timing or falsely veto its active row.
        assertNull(index.focus("iris", 5L, adapter, setOf(6), 1_500L))
        assertNull(index.matchesKnownWindow("iris", 5L, adapter, 6, 1_500L))

        index.remember("iris", 5L, adapter, 6, 5_000L, 6_000L)
        assertNull(index.focus("iris", 4L, adapter, setOf(6), 1_500L))
        assertEquals(6, index.focus("iris", 5L, adapter, setOf(6), 5_500L))
    }

    @Test fun `current playback row wins over lingering prior native active row`() {
        assertEquals(6, AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(5, 6), playbackAdapterPosition = 6,
            previousFocus = 5,
        ))
        assertEquals(5, AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(5, 6), playbackAdapterPosition = 5,
            previousFocus = 6,
        ))
        assertEquals(5, AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(5, 6), playbackAdapterPosition = null,
            previousFocus = 5,
        ))
        assertEquals(6, AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(6), playbackAdapterPosition = 7,
            previousFocus = 5,
        ))
        assertNull(AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = emptySet(), playbackAdapterPosition = 5,
            previousFocus = 5,
        ))
        // A fresh source revision has no trusted previous focus. Until the
        // current holder binds its clock, overlapping rows are ambiguous:
        // leaving the custom blur off preserves Apple's native highlighting.
        assertNull(AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(5, 6), playbackAdapterPosition = null,
            previousFocus = null,
        ))
        assertNull(AppleLyricsBlurTransitionPolicy.clockAwareFocus(
            nativeActive = setOf(5, 6), playbackAdapterPosition = 7,
            previousFocus = 8,
        ))
    }

    @Test fun `failed native active reflection cannot forget a verified previous blur focus`() {
        assertEquals(6, AppleLyricsBlurTransitionPolicy.nextFocusedPosition(
            observedNativeActive = null, resolvedFocus = null, previousFocus = 6,
        ))
        assertNull(AppleLyricsBlurTransitionPolicy.nextFocusedPosition(
            observedNativeActive = emptySet(), resolvedFocus = null, previousFocus = 6,
        ))
        assertEquals(5, AppleLyricsBlurTransitionPolicy.nextFocusedPosition(
            observedNativeActive = setOf(5, 6), resolvedFocus = 5, previousFocus = 6,
        ))
    }


    @Test fun `native blur never clamps bright glyph pixels onto row edges`() {
        assertTrue(source("AppleLyricsBlurEngine.kt").contains("Shader.TileMode.DECAL"))
    }

    @Test fun `do not clear host render effects on rows we never blurred`() {
        assertFalse(AppleLyricsBlurOwnershipPolicy.shouldClearNativeEffect(
            knownBlurred = false, hasAppliedMode = false, trackedBlurred = false,
        ))
        // Only a module-applied effect may be removed; state alone is not ownership.
        assertTrue(AppleLyricsBlurOwnershipPolicy.shouldClearNativeEffect(
            knownBlurred = true, hasAppliedMode = false, trackedBlurred = false,
        ))
        assertTrue(AppleLyricsBlurOwnershipPolicy.shouldClearNativeEffect(
            knownBlurred = false, hasAppliedMode = true, trackedBlurred = false,
        ))
        assertTrue(AppleLyricsBlurOwnershipPolicy.shouldClearNativeEffect(
            knownBlurred = false, hasAppliedMode = false, trackedBlurred = true,
        ))
    }

    @Test fun `zero radius on a fresh focus row never overrides an existing host effect`() {
        assertTrue(AppleLyricsBlurOwnershipPolicy.shouldSkipZeroRadiusRequest(
            radiusPx = 0, previouslyBlurred = false, hasAppliedMode = false,
        ))
        assertFalse(AppleLyricsBlurOwnershipPolicy.shouldSkipZeroRadiusRequest(
            radiusPx = 0, previouslyBlurred = true, hasAppliedMode = true,
        ))
        assertFalse(AppleLyricsBlurOwnershipPolicy.shouldSkipZeroRadiusRequest(
            radiusPx = 8, previouslyBlurred = false, hasAppliedMode = false,
        ))
    }

    @Test fun `dual active native transition has one stable sharp lyric row`() {
        assertEquals(5, AppleLyricsBlurTransitionPolicy.singleActiveFocus(
            nativeActive = setOf(5, 6), previousFocus = 5,
        ))
        assertEquals(6, AppleLyricsBlurTransitionPolicy.singleActiveFocus(
            nativeActive = setOf(6), previousFocus = 5,
        ))
        assertEquals(5, AppleLyricsBlurTransitionPolicy.singleActiveFocus(
            nativeActive = setOf(5, 6), previousFocus = null,
        ))
        assertNull(AppleLyricsBlurTransitionPolicy.singleActiveFocus(
            nativeActive = emptySet(), previousFocus = 5,
        ))
    }

    private fun mainSourceDir(): File =
        listOf(
            File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics"),
            File("src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics"),
        ).first(File::isDirectory)

    private fun source(fileName: String): String = File(mainSourceDir(), fileName).readText()

    companion object {
        // Held strongly so the weak-key method cache cannot drop the key mid-test.
        private val CACHED_CLASS = AppleLyricsBlurStateTest::class.java
        private val OTHER_CLASS = String::class.java
    }

    @Test
    fun `stable blur does not reallocate GPU render effects on every lyric frame`() {
        assertTrue(AppleLyricsBlurApplicationPolicy.canReuseExistingEffect(
            alreadyBlurred = true, currentMode = 1, requestedMode = 1,
            currentRadius = 8f, targetRadius = 8f, animatorRunning = false))
        assertFalse(AppleLyricsBlurApplicationPolicy.canReuseExistingEffect(
            alreadyBlurred = false, currentMode = 1, requestedMode = 1,
            currentRadius = 8f, targetRadius = 8f, animatorRunning = false))
        assertFalse(AppleLyricsBlurApplicationPolicy.canReuseExistingEffect(
            alreadyBlurred = true, currentMode = 1, requestedMode = 1,
            currentRadius = 8f, targetRadius = 11f, animatorRunning = false))
        assertFalse(AppleLyricsBlurApplicationPolicy.canReuseExistingEffect(
            alreadyBlurred = true, currentMode = 1, requestedMode = 1,
            currentRadius = 8f, targetRadius = 8f, animatorRunning = true))
    }

    @Test
    fun `hyper os methods are cached per view class`() {
        val state = AppleLyricsBlurState()
        var resolved = 0
        val first = AppleLyricsHyperOsMethods(setSelfBlur = null, setSelfBlurType = null)
        val value = state.hyperOsMethodsFor(CACHED_CLASS) {
            resolved += 1
            first
        }
        assertSame(first, value)
        // A second lookup for the same class must reuse the cached resolution.
        val again = state.hyperOsMethodsFor(CACHED_CLASS) {
            resolved += 1
            AppleLyricsHyperOsMethods(null, null)
        }
        assertSame(first, again)
        assertEquals(1, resolved)

        val other = AppleLyricsHyperOsMethods(setSelfBlur = null, setSelfBlurType = null)
        assertSame(other, state.hyperOsMethodsFor(OTHER_CLASS) { other })
    }

    @Test
    fun `child adapter position method cache stores and returns by class`() {
        val state = AppleLyricsBlurState()
        val method: Method = String::class.java.getDeclaredMethod("length")
        assertNull(state.childAdapterPositionMethod(String::class.java))
        state.rememberChildAdapterPositionMethod(String::class.java, method)
        assertSame(method, state.childAdapterPositionMethod(String::class.java))
        assertNull(state.childAdapterPositionMethod(Int::class.java))
    }

    @Test
    fun `blur facade no longer declares the runtime state collections`() {
        val hooks = source("AppleLyricsBlurHooks.kt")
        listOf(
            "appleLyricsBlurRuntimeStates",
            "appleLyricsBlurredViews",
            "appleLyricsRecyclerViewsByAdapter",
            "appleLyricsRecyclerViewClassifications",
            "appleLyricsChildAdapterPositionMethods",
            "appleLyricsHyperOsMethods",
        ).forEach { field ->
            assertFalse(
                "Blur 门面不应再声明 $field",
                Regex("(val|var)\\s+$field\\b").containsMatchIn(hooks),
            )
        }
        assertTrue(
            "Blur 门面必须持有状态组件",
            hooks.contains("internal val blurState = AppleLyricsBlurState()"),
        )
    }

    @Test
    fun `engine and recycler utils never touch blur facade state`() {
        val forbidden = listOf(
            "appleLyricsBlurRuntimeStates",
            "appleLyricsBlurredViews",
            "appleLyricsRecyclerViewsByAdapter",
            "appleLyricsRecyclerViewClassifications",
            "appleLyricsChildAdapterPositionMethods",
            "appleLyricsHyperOsMethods",
        ).joinToString("|")
        listOf("AppleLyricsBlurEngine.kt", "AppleLyricsRecyclerUtils.kt").forEach { name ->
            val body = source(name)
            assertFalse(
                "$name 不得直接触碰 Blur 状态集合",
                Regex("\\b(?:$forbidden)\\b").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `duplicated blur constants live only in the blur facade`() {
        val hooks = source("AppleLyricsBlurHooks.kt")
        val supplement = source("AppleLyricsSupplementHooks.kt")
        listOf(
            "APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION",
            "APPLE_LYRICS_SCROLL_STATE_IDLE",
            "APPLE_LYRICS_IDLE_RECHECK_DELAY_MS",
            "APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS",
            "APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS",
            "APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE",
            "APPLE_MUSIC_PACKAGE",
        ).forEach { constant ->
            assertTrue(
                " blur 常量 $constant 必须声明在 Blur 门面",
                Regex("const val $constant\\b").containsMatchIn(hooks),
            )
            assertFalse(
                "SupplementHooks 不应再重复声明 $constant",
                Regex("const val $constant\\b").containsMatchIn(supplement),
            )
        }
    }
}
