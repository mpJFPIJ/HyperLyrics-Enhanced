# Native-Matched Hangul Highlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Match Apple Music's Japanese karaoke highlighting on each corresponding Hangul word/reading while preserving valid placement and full glyphs.

**Architecture:** Retain Apple's word-cell renderer for independently verified sound maps. Repair the timed fallback's display-state transition and paint natural horizontal fill by measured reading glyph geometry. Follow with layout/clip and diagnostics changes, each guarded by a regression test.

**Tech Stack:** Kotlin/Android 16, Apple Music 6.5.3 Xposed/NPatch, JUnit4 via Gradle.

**Spec:** `docs/superpowers/specs/2026-11-24-apple-pronunciation-native-highlight-design.md`.

## Global Constraints

- Use real Apple native word intervals only; do not synthesize a per-syllable clock.
- A line with no verified phonetics may retain a coarse line-wide reading without fabricating finer alignment.
- The Japanese native renderer and click behavior are untouched.
- Show original → matching Hangul → meaning under each visual line.
- Never allow bright inactive lyrics, stale repeats, or trimmed upper/lower glyph strokes.
- Preserve existing changes on the other dirty repository checkout.

## Review Focus

1. Between two verified words of the *same* lyric occurrence, the first Hangul word must stay complete while the next stays dim. Task 1.
2. Between two chorus occurrences with the same text, neither Hangul row may show a previous/future highlight. Task 1.
3. A Hangul glyph halfway through its verified word must have a **left-bright/right-dim** progressive fill, not a flat intermediate tint. Task 2.
4. A one-character native soft-wrap/Japanese compound must never spill a Hangul jamo into another word or hide reading. Task 3.
5. Detached/reused holders must not own duplicate overlays or retain old highlight/clip geometry. Task 4.

---

### Task 1: Preserve Completed Reading Highlights Between Native Word Clocks

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleTimedPronunciationHighlightPolicy.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleTimedPronunciationHighlightPolicyTest.kt`

**Interfaces:** Add `displayVariant(positionMs: Long, variants: List<List<AppleTimedReadingUnit>>): Int?` retaining strict `activeVariant` for speaking-clock diagnosis. ReadingView uses the new display choice to preserve completed word highlights only inside that occurrence's first-to-last verified timestamp bounds.

- [ ] Add a failing test for an inter-word rest, a seek backward, and a second chorus with the same text.
- [ ] Execute `./gradlew :app:testDebugUnitTest --tests '*AppleTimedPronunciationHighlightPolicyTest*'` and confirm the expected behavior failure.
- [ ] Add the minimal `displayVariant` implementation and switch ReadingView's glyph selection while not widening the fast polling scope to off-screen holders.
- [ ] Rerun focused test and complete JVM test suite; inspect actual exit statuses.
- [ ] Commit the narrow change.

### Task 2: Animate Individual Hangul Glyphs With Continuous Horizontal Fill

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Create: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleReadingSweepGeometry.kt`
- Create: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleReadingSweepGeometryTest.kt`

**Interfaces:** `AppleReadingSweepGeometry.plan(progress: Float, left: Float, right: Float, transitionPx: Float)` yields literal pixel gradient stops/colors for one measured Hangul glyph. The style paints full-height strokes and uses the current native word timestamp progress; unknown graphemes stay dim.

- [ ] Add failing tests for 0%, 50%, 100%, a glyph partly crossed by the word-wide sweep, and zero-width/out-of-bounds geometry.
- [ ] Run focused tests to observe failure.
- [ ] Replace flat intermediate `KaraokeSyllableStyle` tint with measured left-bright/right-dim horizontal sweep (using Android `LinearGradient` only after the pure geometry unit is green).
- [ ] Run focused and complete JVM tests and Android compile via CI.
- [ ] Commit change.

### Task 3: Align Pronunciation to Measured Native Visual Rows

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleInterleavedReadingPolicy.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleInterleavedReadingPolicyTest.kt`

**Interfaces:** A verified visual row has exactly one immediately following reading row; unsupported source soft wraps keep a safe fallback and report the inability to interleave. Native line and sound offsets remain immutable.

- [ ] Add failing tests for the video's `脳/みそ`, `フィ` and standalone `ㄴ` cases.
- [ ] Run focused tests to observe failure.
- [ ] Change insertion/row planning to use verified visual line boundaries, excluding phonetic cuts through Unicode grapheme clusters.
- [ ] Run all tests and check the native source remains unchanged.
- [ ] Commit change.

### Task 4: Fix Clip Ownership, Reattach and Diagnostics

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleHangulReadingForeground.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`
- Modify: `app/src/main/java/com/juren233/hyperlyricsenhanced/utils/diagnostics/DiagnosticGitHubUploader.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleSoftWrapResumePolicyTest.kt` (or equivalent focused geometry test)

**Interfaces:** A foreground owner draws once within a measured and non-clipped font ink region; a runtime probe includes session/revision, content-free clip rectangle, native word/reading state, and foreground owner.

- [ ] Add failing geometry and RecyclerView attach/rebind regression tests for half-height mask and duplicate drawing.
- [ ] Run focused tests to observe failure.
- [ ] Correct measured clip ownership and add anonymized UI records to uploader without lyric/credential text.
- [ ] Run full suite and Android compile; inspect NPatch trace if available.
- [ ] Commit change.

### Task 5: End-to-End Verification and Delivery

**Files:** Android test fixtures or CI workflow only if needed.

- [ ] Run JVM suite and `:app:assembleDebug`, using GitHub Actions when Gradle distribution is unavailable locally.
- [ ] Confirm APK extraction/installability and provenance from the matching commit.
- [ ] Reproduce 0:59–1:06, 1:21–1:24, 1:38–1:41, 1:52, 2:10 and 2:44 visual cases on a compatible Android/NPatch session; if no device is available, explicitly mark these as unverified.
- [ ] Deliver branch/PR, verified outputs, test limitations and remaining reproducible defects without declaring total success prematurely.
