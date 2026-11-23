# Remaining Lyrics Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent stale-song translation highlights and recover an offscreen native active lyric after a transient failed follow check, without weakening native karaoke behavior.

**Architecture:** Add pure policies for presentation identity and pending native-follow eligibility. The existing Android adapters provide real live song/revision, active rows and viewport state, while all native painting and scroll requests remain in their current owners.

**Tech Stack:** Kotlin / Android 16, Apple Music 6.5.3 Xposed hook, JUnit, Gradle 9.5 / GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-24-remaining-lyrics-stability-design.md`

## Global Constraints

- Preserve Japanese native word renderer, per-word timing, seek/tap and autofollow whenever already correct.
- Maintain Japanese → Hangul pronunciation → Korean translation and existing feature toggles.
- Do not paint stale translation or re-center while user browsing.
- Build and test on an isolated branch; preserve PR #17 and main; no merge before device validation.

## Review Focus

- Song switches before a new translation getter: old pulse must not focus against the new clock.
- Same song but new translation revision: previous painter must no longer own the display.
- Same source line/adapter with unchanged revision: existing pulse remains eligible for reuse.
- Idle native list after layout transient: pending active line gets a later recheck without repeated continuous scrolling.
- Explicit user drag or song/adapter change before retry: pending recenter must be cancelled.

---

### Task 1: Translation foreground current-presentation ownership

**Files:**
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleNativeTranslationHighlightPolicyTest.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeTranslationHighlight.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementHooks.kt`

**Interfaces:**
- Produce: `AppleTranslationPresentationOwnerPolicy.isCurrent(pulseSongId, pulseRevision, currentSongId, currentRevision): Boolean`.
- Consume: live current song ID and native online translation revision from the hook.

- [x] RED: test that an old pulse is ineligible after song/revision switch even if an old indexed cache still matches.
- [x] Observe expected failure (CI #274: unresolved `AppleTranslationLivePresentationPolicy`).
- [x] Implement the policy and filter obsolete pulses before a shared refresh can render the old source clock.
- [ ] GREEN: targeted tests, full JVM/Lint/APK CI, inspect logs.

### Task 2: Native active-line follow check lost during transient motion

**Files:**
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLyricsFollowRecoveryPolicyTest.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsBlurHooks.kt`
- Modify if necessary: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleMetadataPolicies.kt`

**Interfaces:**
- Consume: host-reported active positions, visible child positions, native scroll state, playing state, user browsing state and owner adapter identity.
- Produce: a bounded, generation-safe follow recheck only when an active row is demonstrably offscreen.

- [ ] RED: test that a transient settling/no-children sample is retried after idle, but explicit drag or adapter change cannot recenter.
- [ ] Verify expected RED with CI, then implement the smallest guarded recheck.
- [ ] GREEN: full tests/Lint/APK; no repeated recenter/extra row painting.

### Task 3: Device-only issues and verification gate

**Files:**
- Existing diagnostics: `AppleLyricUiRuntimeProbe` stages and on-device export path.

- [ ] Audit whether all translation, pronunciation and auto-scroll paths have enough conditional diagnostic evidence; do not add high-frequency log spam.
- [ ] Verify debug builds, no instrumentation or debug-only behavior in release.
- [ ] Leave a precise device reproduction list for soft-wrap rows, seek, scroll, song switch, duplicate translation and performance.
- [ ] Do not mark visual issues fixed without an Android 16 / Apple Music 6.5.3 / NPatch run.
