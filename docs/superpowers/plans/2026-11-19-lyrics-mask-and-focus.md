# Lyric Mask and Focus Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove native-gradient splitting from Korean reading and make Korean meaning focus agree with the actual host-active lyric while reducing UI work.

**Architecture:** A layout-only reading TextView owns the vertical space but a single outer-holder drawable paints its existing timed syllables above the native gradient. A group-level native-activity resolver chooses translation focus; one manager ticker repaints each visible group once.

**Review amendment:** The overlay affects injected fallback ReadingView only;
verified native Hangul word cells use Apple's timing renderer. Diagnose and
protect that second route without hiding or replacing its karaoke spans. Native
host RecyclerViews have a different AndroidX classloader. The blur clock mapper
must use holder-observed native times rather than equating logical song indexes
with adapter positions. Keep the shared ticker dormant without attached rows.

**Tech Stack:** Kotlin Android Views, LibXposed, JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-19-lyrics-mask-and-focus-design.md`

## Global Constraints

- Preserve Apple Music 6.5.3, NPatch and native Japanese objects/word animation.
- Preserve source → Hangul reading → Korean translation layout and each toggle.
- Do not merge or overwrite #227 or the previous local dirty worktree.
- Treat device-level clipping, jank and sync as unconfirmed until Galaxy Buddy3 validation.

## Review Focus

1. Recycled holder after an overlay is attached must remove the previous drawable, with no duplicate paint.
2. A lyric with no native-active adapter row must not have an unrelated translation emphasized.
3. Two native-active lines with distinguishable clock windows must select the currently playing one, not whichever holder's pulse happens to run.
4. Paused/seek clock changes must repaint without a delayed stale foreground.
5. Unmeasured/detached reading must fall back to its native TextView instead of disappearing.

### Task 1: Single reading foreground owner

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Create: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleHangulReadingForeground.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLineWidePronunciationRowPolicyTest.kt`

**Interfaces:** A pure `AppleHangulReadingPaintPolicy` chooses `NATIVE` before layout/when detached and `OUTER_OVERLAY` after attached and measured; the ReadingView owns one drawable added to `holderRoot.overlay`, reused on identical binds, removed on detach/clear. Draw the existing text layout with its live syllable spans.

- [ ] Write failing policy tests for no duplicate paint, no offscreen drawing, and unmeasured fallback.
- [ ] Run tests and verify RED (CI if local Gradle unavailable).
- [ ] Implement minimal overlay with attach, invalidation and lifecycle protection.
- [ ] Run tests and full suite; verify GREEN.
- [ ] Commit.

### Task 2: One native-authoritative Korean meaning focus

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeTranslationHighlight.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementHooks.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementPronunciationSupport.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleNativeTranslationHighlightPolicyTest.kt`

**Interfaces:** Pass the binding adapter position to the translator painter and a host active-position provider at construction. Choose among native-active bound rows using their source timestamps when available. If the host offers no active state, use the bounded source clock only. Recompute all bound rows with a single group-level scheduler.

- [ ] Write failing tests for noncurrent dim, active tie-break, no-clock and missing-holder positions.
- [ ] Run tests and verify RED.
- [ ] Implement focus policy, binding position, and coalesced refresh ticker.
- [ ] Run tests and full suite; verify GREEN.
- [ ] Commit.

### Task 3: Recheck stale native focus and UI cost

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleMetadataPolicies.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsBlurHooks.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLyricsBlurStateTest.kt`

**Interfaces:** A clock-aware focus policy chooses among simultaneously active host adapter positions by genuine line begin times; no arbitrary persistent old focus when the clock indicates a new lyric. Reuse existing blur recenter and viewport guards. Add bounded diagnostics for multiple focused native rows; no destructive native-word recoloring without device evidence.

- [ ] Write failing tie-break tests, including no-clock fallback and simultaneous-active source lines.
- [ ] Verify RED; implement minimal policy and call-site change.
- [ ] Verify GREEN with full unit tests, Lint, APK build and signature/roundtrip.
- [ ] Commit to a new patch branch; leave PR as draft pending device verification.
