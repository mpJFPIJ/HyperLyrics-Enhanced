# Soft-wrap overlay reattachment implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the same verified Hangul reading overlay after temporary Android holder detach/reattach without reusing stale source geometry.

**Architecture:** A pure Kotlin compatibility policy checks immutable source geometry. The existing Android overlay keeps its source snapshot and reinstalls its painter/line spacing on the `ReadingView`'s measured attached path, while a full clear still disposes the relationship.

**Tech Stack:** Kotlin, Android `TextView`, `Layout`, `ViewOverlay`, JUnit, Gradle 9.5.

**Spec:** `docs/superpowers/specs/2026-11-23-soft-wrap-overlay-reattach-design.md`

## Global Constraints

- Preserve native source rendering, Japanese word timing, and seeking.
- Preserve NPatch / Apple Music 6.5.3 / Android 16 compatibility.
- Do not reattach stale or unmeasured drawable geometry.
- Do not merge or claim device success without actual device verification.

## Review Focus

- Same number of internal rows but different UTF-16 wrap boundaries: reject reuse.
- Source text of identical length but different lyrics: reject reuse.
- Reattachment while source/root layout is pending: defer rather than paint stale offsets.
- Holder source font or width changed while detached: reject reuse.
- Other code changed source line spacing: do not overwrite its ownership.

---

### Task 1: Verify source geometry across temporary reattachment

**Files:**
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLineWidePronunciationRowPolicyTest.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`

**Interfaces:**
- Produces: `AppleSoftWrapResumeGeometry` and `AppleSoftWrapResumePolicy.canResume(...)` for checked source snapshots.

- [ ] Write tests for matching bounds, changed bounds/text/font/width, pending layout, and line-spacing ownership.
- [ ] Run those tests and verify expected RED (or record Gradle unavailable and use remote CI).
- [ ] Implement the minimal pure geometry validation in the overlay source.
- [ ] Run tests and verify GREEN.

### Task 2: Restore the installed overlay on the next valid attached layout

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLineWidePronunciationRowPolicyTest.kt`

**Interfaces:**
- Consumes: `AppleSoftWrapResumePolicy.canResume(...)`.
- Produces: `AppleNativeSoftWrapReadingOverlay.resumeIfCompatible(): Boolean`.

- [ ] Add a regression case for a detached reading that still has the same verified source geometry and a recycled holder that does not.
- [ ] Run the RED test remotely if local Gradle remains unavailable.
- [ ] Keep the verified overlay reference across temporary detach; clear drawing/spacing, then attempt conditional reinstall on attached paint/layout callbacks. Full unbind still releases it.
- [ ] Run focused and full tests, Lint and APK CI; inspect all failures and resolve before completion.
- [ ] Leave work unmerged pending the target-phone test of detach/reattach, scrolling, seeking, grouped karaoke and translation.
