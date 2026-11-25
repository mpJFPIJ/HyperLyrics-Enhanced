# Apple Music Lyric Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize Korean lyric rows, translation and blur ownership, and time/scroll transitions without removing existing features.

**Architecture:** Native adapter remains the owner of lyric content and scrolling. A single outermost holder bind reconciles optional Hangul/translation children; refresh requests collapse into one latest frame request. Blur effects are touched only when owned by the module and focus tracks one stable native row across transition overlap.

**Tech Stack:** Kotlin, Android Views, Xposed, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-19-lyrics-stability-design.md`

## Global Constraints

- Keep Apple Music 6.5.3 and NPatch compatibility.
- Preserve three-line Japanese / Hangul reading / Korean translation order and current-only meaning highlight.
- Do not modify unrelated user edits in the dirty worktree.
- Do not claim device-level confirmation from older #217 logs.

## Review Focus

- Nested two/three-argument holder binds operate on the same holder without deleting its reading twice.
- A recycled holder with different source words must never retain the prior Korean reading.
- A revision change in the same frame must lead to one latest adapter refresh.
- Native blur removal must not erase an effect owned by the host.
- Native dual-active transition and a seek must not simultaneously focus unrelated lyric rows.

---

### Task 1: Holder lifecycle and reading reuse

**Files:** `AppleLyricsDiagnostics.kt`, `AppleLineWidePronunciationRow.kt`, `AppleLyricsSupplementHooks.kt`, targeted JUnit tests.

**Interfaces:** `AppleLyricsBindingPassPolicy.shouldApplyVisualBind`, `AppleLineWidePronunciationRow.bind`.

- [ ] Write tests for nested/sibling bind policy and unchanged/changed reading identity.
- [ ] Confirm tests fail on the baseline.
- [ ] Gate duplicate nested callbacks and retain unchanged attached readings.
- [ ] Run targeted and full unit tests; inspect regressions.

### Task 2: Coalesced native refresh

**Files:** `AppleLyricsRefreshBurstGate.kt`, `AppleLyricsSupplementHooks.kt`, `AppleLyricsRefreshBurstGateTest.kt`.

**Interfaces:** single latest pending `AppleLyricsRefreshKey` and combined visibility flag.

- [ ] Write failing test for different revisions coalescing in the same frame.
- [ ] Implement one scheduled frame refresh that consumes the latest valid request.
- [ ] Run targeted and full unit tests.

### Task 3: Blur ownership and focus

**Files:** `AppleLyricsBlurEngine.kt`, `AppleLyricsBlurHooks.kt`, related blur policy tests.

**Interfaces:** renderer-owned cleanup predicate and single native focus selection.

- [ ] Write tests for not clearing unrelated host effects and transition focus selection.
- [ ] Preserve host effects and choose a stable one-line focus while leaving native activation intact.
- [ ] Run targeted and full unit tests.

### Task 4: Playback and translation transition stability

**Files:** `AppleLineWidePronunciationRow.kt`, `ApplePausedPlaybackClockPolicy.kt`, `AppleNativeTranslationHighlight.kt`, related tests.

**Interfaces:** imminent reading wakeup and bounded stale-clock fallback.

- [ ] Write tests for imminent reading transitions and stale playback fallbacks.
- [ ] Apply minimal scheduling and clock hardening, without adding competing timers.
- [ ] Run targeted, full unit tests, lint and Android build.

### Task 5: Final verification

- [ ] Review the entire diff against the eight user symptoms and original code behavior.
- [ ] Verify tests and build using local tools or CI; explicitly report environment gaps.
- [ ] Leave branch changes recoverable and provide a reviewable result.
