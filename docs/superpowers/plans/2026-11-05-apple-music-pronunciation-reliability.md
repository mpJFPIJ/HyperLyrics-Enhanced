# Apple Music Korean Pronunciation Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove truncated, wrongly segmented and missing Korean pronunciation in Apple Music by replacing guessed proportional karaoke distribution with source-verified word alignment plus full-line presentation fallback.

**Architecture:** The Enhanced service analyzes each original line once with Sudachi, emits source-offset reading spans and a full Hangul line; Apple Music verifies native word boundaries and displays either a truthful aligned word track or an independent full-width line under the source lyric. Translation/cache remain independent. A privacy-safe build and pipeline fingerprint makes on-device behavior verifiable.

**Tech Stack:** Android/Kotlin, Sudachi Java, kotlinx.serialization/AIDL, Xposed/NPatch Local, Apple Music 6.5.3 RecyclerView hooks, JUnit/Gradle, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-05-apple-music-pronunciation-reliability-design.md`

## Global Constraints

- Do not alter original Japanese lyric text, native Apple word timestamps, translation meaning, or functioning system hooks.
- Retain Gemini/other OpenAI-compatible providers, Cohere configuration, multi-key rotation, cached translations, Korean UI and visibility toggles.
- Preserve Apple Music 6.5.3 on the user's Galaxy Buddy3 with NPatch **Local**; no uninstall, repatch, extra paid service, or external credential movement.
- Keep all new transport fields optional with defaults; old serialized songs still decode.
- The signing identity must remain personal-updatable; never merge experimental work to `main` before device validation.
- A log may contain identifiers, counts, states and cryptographic content hashes but **never** lyric text, full song titles, response bodies, tokens or API keys.

## Review Focus

1. Apple native timed words divide a single morphological token: render complete full-width line, never guess time or syllable boundaries (Task 3, Task 4).
2. Japanese full-line vs separately analyzed fragment produce different readings: source-offset single-pass wins (Task 2).
3. RecyclerView holder reused for another lyric/song during async update: no stale overlay or accidental cross-song display (Task 4, Task 5).
4. AI returns 22 translations but only 17 readable lines: keep translations, classify every missing pronunciation and don't display false `complete` (Task 2, Task 5).
5. Release-signed build has same `versionName/versionCode` as prior build: logs still reveal exact CI run/commit loaded by NPatch (Task 1).

---

### Task 1: Prove exactly which APK and module execute

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android-release.yml`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/HookEntry.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/service/AppleMusicAiTranslationService.kt`
- Create: `app/src/main/java/com/juren233/hyperlyricsenhanced/common/diagnostics/LyricBuildIdentity.kt`
- Test: `app/src/test/java/com/juren233/hyperlyricsenhanced/common/diagnostics/LyricBuildIdentityTest.kt`

**Interfaces:** `LyricBuildIdentity(label: String, commit: String, ciRun: String)` and `safeStage(stage: String, stats: Map<String, Int>, reason: String? = null)`; CI feeds nonsecret commit/run strings to Gradle build fields.

- [ ] Write failing tests that reject blank CI identity on personal signed builds and ensure diagnostic values never include raw lyric/API data.
- [ ] Run the focused JUnit class and confirm failure.
- [ ] Add optional `-P` build identity, log it once from unconditional `HookEntry.onModuleLoaded` and once in the app translation request, and add bounded release-safe stage diagnostics (not gated by `BuildConfig.DEBUG`).
- [ ] Re-run tests; verify the signing workflow includes CI run+short SHA in both app and loaded-module logs without changing package ID/signature or requiring new permissions.
- [ ] Commit only this task after review.

### Task 2: Build one context-aware Japanese reading per source line

**Files:**
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/SudachiReadingEngine.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/LocalJapanesePronunciation.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/JapaneseToHangul.kt`
- Create: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/SourceReadingSpan.kt`
- Test: `app/src/test/java/com/juren233/hyperlyricsenhanced/root/aitrans/SourceReadingSpanTest.kt`
- Test: `app/src/test/java/com/juren233/hyperlyricsenhanced/root/aitrans/LocalJapanesePronunciationTest.kt`

**Interfaces:** `SudachiReadingEngine.analyzeLine(text: String): List<SourceReadingSpan>?`; `SourceReadingSpan(sourceStart: Int, sourceEnd: Int, sourceText: String, kana: String, hangul: String)`; define `LineReadingResult(text: String?, spans: List<SourceReadingSpan>, status: LineReadingStatus)` and `LineReadingStatus` (`COMPLETE`, `MISSING_UNSUPPORTED`, `EMPTY`) in `SourceReadingSpan.kt`. `LocalJapanesePronunciation.generateLine(text: String): LineReadingResult` returns the full-line reading and its immutable spans.

- [ ] Add failing tests for repeats, context-sensitive kanji, kana compounds, particles `は/へ/を`, long vowels, `っ`, `ん`, punctuation, whitespace, Unicode normalized text and unknown/OOV terms.
- [ ] Confirm tests fail against isolated-word re-analysis and current kana rules where applicable.
- [ ] Tokenize each *whole line once* and preserve original offsets; convert kana as mora groups and expose explicit `MISSING_UNSUPPORTED` for unrecoverable segments. Keep conservative English dictionary; never invent filler readings.
- [ ] Re-run tests; validate that span ranges are monotonic, source substrings exact and concatenated verified Hangul equals the full reading, excluding only punctuation/spacing.
- [ ] Commit the reading stage.

### Task 3: Strict source-to-Apple karaoke word alignment

**Files:**
- Create: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/AppleWordReadingAlignment.kt`
- Create: `app/src/main/java/com/juren233/hyperlyricsenhanced/lyric/model/LyricPronunciationSpan.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/lyric/model/LyricWordPronunciation.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/lyric/model/RichLyricLine.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/service/AppleMusicAiTranslationService.kt`
- Test: `app/src/test/java/com/juren233/hyperlyricsenhanced/root/aitrans/AppleWordReadingAlignmentTest.kt`

**Interfaces:** `AppleWordReadingAlignment.align(original: String, appleWords: List<String>, spans: List<SourceReadingSpan>): WordAlignmentResult`; define `WordAlignmentResult` (`Verified(words: List<LyricWordPronunciation>)` or `LineWide(reason: AlignmentFailure)`) in `AppleWordReadingAlignment.kt` with no invented timing. Add `@Serializable LyricPronunciationSpan(start: Int, end: Int, source: String, hangul: String)` in the shared lyric/model package and nullable `RichLyricLine.pronunciationSpans` plus `pronunciationStatus`; adapter converts internal `SourceReadingSpan` to shared transport spans without importing the AI service's internals into the model.

- [ ] Add failing tests for identical boundaries, whitespace/HTML, repeated same-looking words, split kanji compounds, mismatch between original and native text, and a stale result for another line.
- [ ] Confirm each incompatible boundary fails closed (line-wide), never returns a guessed `Verified` map.
- [ ] Implement exact source-offset/word-concatenation normalization and complete Hangul identity checks; replace `LocalJapanesePronunciation.alignedWords()` per-word dictionary calls with the new full-line span alignment.
- [ ] Test serialize/deserialize against old and new song payloads, as well as translation-only and pronunciation-only results.
- [ ] Commit the alignment/transport stage.

### Task 4: Dedicated complete-line rendering fallback in Apple Music

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/ApplePronunciationPolicy.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementPronunciationSupport.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementStateOwners.kt`
- Create: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/ApplePronunciationPolicyTest.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLineWidePronunciationRowPolicyTest.kt`

**Interfaces:** `ApplePronunciationPolicy.presentationMode(line: LineReadingResult, verified: WordAlignmentResult): PronunciationPresentationMode` (define the enum in `ApplePronunciationPolicy.kt`, values `OFFICIAL`, `ALIGNED_WORDS`, `LINE_WIDE`, `HIDDEN`); `AppleLineWidePronunciationRow.bind(holder: Any, songId: String, lineKey: String, hangul: String, visible: Boolean)` and `clear(holder: Any)`.

- [ ] Add failing tests showing current proportional fallback preserves concatenated text but fails to preserve *semantic source-word alignment*; assert guessed per-word distribution is prohibited.
- [ ] Before editing the native hook, capture read-only traces for Apple Music 6.5.3 `A.p/q` holder and parent width; establish a stable lyric-line anchor. If unavailable, stop this task and report the incompatibility, not a guessed patch.
- [ ] Implement verified-word rendering with native timestamps only. For unmatched spans, suppress the old per-word pronunciation injection and attach a separate line-wide row at the original lyric's holder, measured to container width with wrapping; retain translation and backing-vocal spacing.
- [ ] Add holder lifecycle cleanup on rebind/recycle, scroll and refresh; protect against stale songId/lineKey/revision, toggles and source switches. Do not create synthetic Apple `LyricsWord` objects.
- [ ] Test layout-policy logic for 320/360/430px equivalent widths, large text, 1–3 line wraps, empty text, RTL/mixed punctuation, and repeated holder reuse. Confirm no clipped/cross-song characters in device checks before approval.
- [ ] Commit the presentation stage.

### Task 5: Cache, state, completion and error semantics

**Files:**
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/AITranslationKey.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/AITranslator.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/root/aitrans/AiLyricComposition.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleNativeOnlineTranslationStore.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleSupplementDataReceive.kt`
- Test: `app/src/test/java/com/juren233/hyperlyricsenhanced/root/aitrans/AiLyricCompositionTest.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleNativeOnlineTranslationStoreTest.kt`

**Interfaces:** Versioned pronunciation key includes reading policy revision; `LineReadingResult.status` and line-indexed `presentationMode` remain independent of translation acceptance or cached AI model identity.

- [ ] Test cached legacy romanization, correct translation/absent reading, five unsupported readings, rate-limited or expired Cohere key, and partial AI response; translation must remain visible.
- [ ] Test hide/show, cache-delete during in-flight request, old results arriving after song change, and duplicate callback; show no stale pronunciation or resurrected cache.
- [ ] Change *pronunciation cache version only*, preserve translation records and manual corrections; store explanation for skipped lines and prevent false `complete` status on partial phonetics.
- [ ] Confirm no unnecessary model calls for already translated songs and no extra paid API requirements.
- [ ] Commit the state/cache stage.

### Task 6: Device-grade evidence and regression matrix

**Files:**
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/utils/diagnostics/DiagnosticPrivacyPolicy.kt`
- Modify: `app/src/test/java/com/juren233/hyperlyricsenhanced/utils/diagnostics/DiagnosticPrivacyPolicyTest.kt`
- Create: `docs/APPLE_PRONUNCIATION_DEVICE_REGRESSION.md`
- Modify: `.github/workflows/android-release.yml` (only if additional artifact metadata check is needed)

**Interfaces:** Report `buildId`, `originalLines`, `translationLines`, `readingLines`, `alignedLines`, `lineWideLines`, `missingLines`, `rowBound`, `rowOverflow`, and categorical failure reasons; all bounded and privacy-safe.

- [ ] Test diagnostics privacy and finite event count, including malformed song, network failure and module reload.
- [ ] Run focused JUnit red/green for every preceding task, then `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and applicable release ABI/signing workflow checks; save actual results, never infer success.
- [ ] Run a release-signed draft-PR GitHub Actions build with the existing personal keystore, verify version/build stamp in both app and NPatch log and deliver the exact signed APK artifact link.
- [ ] On the user's Galaxy Buddy3 / Android 16 / Apple Music 6.5.3 / NPatch Local, inspect the reported `IRIS OUT` and two other songs: initial load, 100% long-line visibility, verified word sync, mixed text, back vocals, scrolling, seek, repeat, pause, hide/show, source change, app restart and offline/expired key.
- [ ] Require zero truncated or foreign-line phonetics on the sampled screens, zero native timestamp changes, every unsupported line accounted for, and no loss of translation, app-data or preexisting features; if any fails, do not declare fixed.
- [ ] Review draft PR, preserve stable `main`, and merge only after user approval and actual device evidence.

## Handoff / stop conditions

- Execute in tasks 1 → 6 order, TDD and one isolated commit per task, with independent review and regression evidence.
- Stop before any Apple Music app uninstall, repatch, permission expansion or credentials relocation; none is required by this plan.
- Stop if the Apple 6.5.3 lyric holder cannot safely anchor a full-width supplemental line; report the observed obstacle and revise the presentation design rather than shipping another proportional hack.
- **Do not label a successful CI build an end-to-end fix.** Keep the experimental PR draft until phone screenshots and NPatch/app logs agree.
