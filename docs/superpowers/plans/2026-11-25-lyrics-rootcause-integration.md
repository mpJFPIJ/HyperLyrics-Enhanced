# Lyrics Root-Cause Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make native Japanese, measured Hangul pronunciation and Korean meaning display and highlight consistently on Apple Music 6.5.3 without introducing fabricated timing or regressing NPatch Local.

**Architecture:** Preserve the Apple Japanese renderer and its word clocks. Repair language-track selection, share a strict source/reading proof, establish one measured owner for fallback placement/painting, then unify live playback/holder ownership across pronunciation, meaning and scroll.

**Tech Stack:** Kotlin, Android views, Xposed/NPatch, Apple Music 6.5.3, JUnit and Gradle.

**Spec:** `docs/superpowers/specs/2026-11-25-lyrics-rootcause-integration-design.md`

## Global Constraints

- Apple Music 6.5.3 only; 7.0.3 postponed.
- Existing rootless NPatch Local installation, Japanese native karaoke, translation and pronunciation cache are preserved.
- No unverified per-syllable/per-word clock interpolation.
- No intermediate CI/APK builds. The user explicitly requested one final integrated verification.
- Never merge PR #20 or claim device success based only on Kotlin CI.

## Review Focus

1. Mixed Japanese Kanji/kana official `ja-Hang`/`ja-Kore` track competes with available Latin and AI fallbacks — verify selected content and native timestamps.
2. Several native source children with one or more internally soft-wrapped TextViews — Hangul cannot pool after all source rows or attach to the wrong row.
3. Two repeated identical choruses and a backward/forward seek — stale timer, adapter row or old song never owns a new highlight.
4. Rotated/narrow/large-font layout followed by holder detach and reattach — no clipped glyph, duplicate paint, stale soft-wrap gap or orphan row.
5. Missing/partial phonetics, supplementary Han, kana digraph, orphan jamo and delayed translation fragment — no invalid split, lost original, invented clock or double meaning.

---

### Task 1: Complete evidence and cause ledger

**Files:** Update `docs/diagnostics/2026-11-20-lyrics-cause-ledger.md`, referencing the current spec and new video, without treating the older #289 ZIP as #306 runtime evidence.

- [ ] Trace raw TTML/request languages → Apple native song model → selected official/AI phonetics → holder rows, with provenance and revision at each boundary.
- [ ] Trace source native word clock, reading fallback pulse, translation focus and auto-follow independently on seek/paused/recyle paths.
- [ ] Record the exact Flexbox/holder child-count and clip contract that would make an outer reading implementation safe.
- [ ] Mark every defect as code-proven, device-observed only, or unsupported; name a focused regression for each code-proven case.

### Task 2: Consistent official Japanese Hangul language policy

**Files:** `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/ApplePronunciationPolicy.kt`, `lyrics/AppleNativePronunciationLanguagePolicy.kt`, `lyrics/AppleSupplementTextHooks.kt`, `metadata/AppleMetadataPolicies.kt`; matching policy tests.

**Interfaces:** Preserve `AppleNativePronunciationLanguagePolicy.select(languages, hangulFirst, allowNative, hasLatinText, supplementLanguage): String?`; unify official match and explicit application under the same priority/availability policy.

- [ ] Add tests showing `ja-Hang` and `ja-Kore` are requested/selected when available and selected by user priority; preserve Latin preference, Mandarin hiding, native-off and fallback behavior.
- [ ] Confirm those assertions fail on the original source for the missing request/match cases; user-requested Gradle runs remain deferred until the final test gate.
- [ ] Implement a consistent choice across request candidate expansion, runtime matching and explicit song selection. Instrument track availability versus successful `setPronunciation` separately.
- [ ] Verify source diff and test source changes; keep the change independently reviewable.

### Task 3: Correct measured Japanese↔Hangul row pairing

**Files:** `lyrics/AppleInterleavedReadingPolicy.kt`, `lyrics/AppleNativeSoftWrapReadingOverlay.kt`, `lyrics/AppleLineWidePronunciationRow.kt`, `root/aitrans/VerifiedJapaneseFragmentReading.kt`; associated tests.

**Interfaces:** `AppleInterleavedReadingPolicy.plan(sourceText, pronunciation, cells, spans): List<AppleInterleavedReadingRow>?` continues to fail closed; a new geometry plan must explicitly represent internal native TextView soft lines and reject contradictory offsets rather than guessing them.

- [ ] Add layout policy regressions for multiple source cells with internal soft wraps, mixed physical/logical lines, source-only remeasurement, UTF-16 boundaries and invalid span coverage.
- [ ] Prove each proposed visual row's actual source and reading ranges; no source-count/width proportional split.
- [ ] Implement source-preserving interleaving only when 6.5.3 geometry supports it; unknown layouts keep complete reading and diagnostic status, never invented word placement.
- [ ] Exercise view reuse, stale measurement, width change and independent pronunciation toggles in policy/lifecycle tests.

### Task 4: Eliminate competing native-gradient and fallback paint ownership

**Files:** `lyrics/AppleLineWidePronunciationRow.kt`, `lyrics/AppleHangulReadingForeground.kt`, `lyrics/AppleNativeSoftWrapReadingOverlay.kt`; paint ownership tests.

- [ ] Add ownership tests for alpha/gradient native fallback transitions, overlay attach/detach, layout invalidation, identical source and changed source cases.
- [ ] Separate fallback paint from Apple's masked original hierarchy without replacing native Japanese source words or their touch target.
- [ ] Ensure one reading owner at a time; remove old overlays and modified line spacing atomically when binding/scope changes.
- [ ] Confirm actual pixel clipping and partial-white behavior only during final on-device replay.

### Task 5: Harmonize temporal and adapter ownership

**Files:** `lyrics/AppleTimedPronunciationHighlightPolicy.kt`, `lyrics/AppleNativeTranslationHighlight.kt`, scroll-follow policies and playback hooks; corresponding tests.

- [ ] Add tests for seek backward/forward, pause/resume, old asynchronous position, stale holder generation, repeated chorus, song/revision transition and user drag.
- [ ] Make live playback selection and adapter/source ownership checks consistent without overriding Apple's accepted Exo clock.
- [ ] Ensure a missing clock/ambiguous word keeps unsupported fragments dim, and current meaning group alone can focus.
- [ ] Remove redundant periodic invalidations only when they are proven unnecessary; quantify device frame times at final replay.

### Task 6: Final integrated verification and handoff

- [ ] Review every new test against a concrete regression that would fail before the repair; check all previous requirements and source diff.
- [ ] Run targeted JVM tests, full unit suite, Debug/Release Lint, signed Debug/Release build, ABI and artifact byte/hash checks as one coordinated final verification phase.
- [ ] Keep PR draft; publish only verified changes and provide the exact commit, CI run, SHA-256, and downloadable APK after success.
- [ ] Ask for (or use available) matching real-device video and NPatch diagnostics from the new build; do not call visual behavior fixed without them.
