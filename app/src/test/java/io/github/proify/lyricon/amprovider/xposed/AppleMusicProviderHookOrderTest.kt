/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppleMusicProviderHookOrderTest {

    @Test
    fun `provider installs hook groups in the protected baseline order`() {
        assertEquals(
            PROTECTED_HOOK_ORDER,
            AppleMusicProvider.hookModuleIdsForBuild(debug = true),
        )
        assertEquals(
            PROTECTED_HOOK_ORDER.filterNot(DEBUG_ONLY_HOOKS::contains),
            AppleMusicProvider.hookModuleIdsForBuild(debug = false),
        )
    }

    @Test
    fun `signed CI debug APK retains playback hooks but excludes expensive UI diagnostics`() {
        val ids = AppleMusicProvider.hookModuleIdsForBuild(
            debug = true, ciBuildRun = "43856218853",
        )
        assertTrue("Needed karaoke binder missing", "hookAppleLyricsBindingDiagnostics" in ids)
        assertTrue("Native pronunciation renderer missing", "hookApplePronunciationWordRendering" in ids)
        for (id in DEBUG_ONLY_HOOKS) {
            org.junit.Assert.assertFalse("Unexpected expensive CI diagnostic hook: $id", id in ids)
        }
    }

    @Test
    fun `production must install native lyric holder binding for Korean translation and Hangul rendering`() {
        val release = AppleMusicProvider.hookModuleIdsForBuild(debug = false)
        assertTrue("Release cannot render synced Korean translations without the holder hook",
            "hookAppleLyricsBindingDiagnostics" in release)
        assertTrue("Expensive native UI diagnostics should remain debug-only",
            "hookAppleLyricsUiDiagnostics" !in release)
    }

    @Test
    fun `Apple Music remote preference listener keeps a strong owner reference`() {
        // R6 拆分后：强引用字段声明在目录/语言组装群，监听器接线代码位于内容语言偏好扩展文件。
        fun readSource(fileName: String): String {
            val relativeSourcePath =
                "src/main/java/io/github/proify/lyricon/amprovider/xposed/$fileName"
            return listOf("app/$relativeSourcePath", relativeSourcePath)
                .map(::File)
                .first(File::isFile)
                .readText()
        }
        val catalogLanguageSource = readSource("AppleOrchestratorCatalogLanguageAssembly.kt")
        val wiringSource = readSource("AppleOrchestratorContentUiLanguage.kt")
        val listenerField = "var contentUiLanguagePreferenceListener:"
        val listenerAssignment = "catalogLanguage.attachPreferenceListener(listener)"
        val listenerRegistration = "prefs.registerOnSharedPreferenceChangeListener(listener)"

        assertTrue(
            "Remote SharedPreferences listeners are weakly held and need a strong field owner",
            catalogLanguageSource.contains(listenerField),
        )
        assertTrue(
            "The strong listener reference must be assigned before registration",
            wiringSource.indexOf(listenerAssignment) in 0 until wiringSource.indexOf(listenerRegistration),
        )
        assertTrue(
            "Volume balance changes must emit a runtime diagnostic before reconciliation",
            wiringSource.contains("event=preference_changed") &&
                wiringSource.contains("playbackHooks.onVolumeBalancePreferenceChanged()"),
        )
    }

    @Test
    fun `missing lyrics refresh preserves native return to lyrics callback order`() {
        val calls = mutableListOf<String>()

        refreshMissingLyricsNowPlaying(
            mediaId = "635770202",
            refreshMetadataCallbacks = { id -> calls += "metadata:$id" },
            refreshPlaybackItemBindings = { id -> calls += "binding:$id" },
        )

        assertEquals(
            listOf("metadata:635770202", "binding:635770202"),
            calls,
        )
    }

    private companion object {
        val PROTECTED_HOOK_ORDER = listOf(
            "hookActivityThemeInitialization",
            "hookMetadataSurfaceLifecycle",
            "hookTranslationPreference",
            "hookMediaApiLocalization",
            "hookContentHttpLocalization",
            "hookAmpApiHttpLocalization",
            "hookCatalogRequestLocalization",
            "hookExoMediaPlayer",
            "hookAtmosVolumeDiagnostics",
            "hookMediaMetadataChange",
            "hookContentItemMetadata",
            "hookInAppLibraryEntities",
            "hookCollectionPageMetadataRefresh",
            "hookArtistProfileTopSongs",
            "hookArtistProfileMetadata",
            "hookRecentlySearchedMetadata",
            "hookInAppArtworkContinuity",
            "hookInAppListenNowArtworkContinuity",
            "hookInAppLibraryEpoxyRefresh",
            "hookInAppLibraryComposeRefresh",
            "hookDebugListenNowArtworkLifecycle",
            "hookVisibleMetadataDiagnostics",
            "hookInAppDataBindingRefresh",
            "hookInAppListenNowMetadataBinding",
            "hookBrowseSurfaceMetadata",
            "hookRecyclerViewCentralBinding",
            "hookInAppMetadata",
            "hookInAppPlaybackItemConversion",
            "hookInAppActionSheetMetadata",
            "hookSettingsCellularDataEntry",
            "hookCellularAvailability",
            "hookMediaSessionMetadata",
            "hookMediaSessionQueue",
            "hookPlaybackNotificationMetadata",
            "hookAppleOfficialPronunciationLanguageMatching",
            "hookAppleLyricsPreferredLanguages",
            "hookApplePronunciationWordRendering",
            "hookLyricBuildMethod",
            "hookAppleNativeLyricsPresentation",
            "hookAppleSystemFontWeight",
            "hookAppleLyricsBlurEffect",
            "hookAppleLyricsUiDiagnostics",
            "hookAppleLyricsBindingDiagnostics",
            "hookAppleLyricsSourceMenu",
            "hookAppleMissingLyricsSupplement",
            "hookLyricsNetworkRequest",
            "hookLyricsCookies",
            "hookFinalLyricsHttp",
        )

        val DEBUG_ONLY_HOOKS = setOf(
            "hookAtmosVolumeDiagnostics",
            "hookDebugListenNowArtworkLifecycle",
            "hookVisibleMetadataDiagnostics",
            "hookAppleLyricsUiDiagnostics",
            "hookLyricsNetworkRequest",
            "hookLyricsCookies",
            "hookFinalLyricsHttp",
        )
    }
}
