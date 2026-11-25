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

- [x] Write tests for matching bounds, changed bounds/text/font/width, pending layout, and line-spacing ownership.
- [x] Run those tests and verify expected RED (CI #269).
- [x] Implement the minimal pure geometry validation in the overlay source.
- [x] Run tests and verify GREEN (CI #270).

### Task 2: Restore the installed overlay on the next valid attached layout

**Files:**
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleLineWidePronunciationRowPolicyTest.kt`

**Interfaces:**
- Consumes: `AppleSoftWrapResumePolicy.canResume(...)`.
- Produces: `AppleNativeSoftWrapReadingOverlay.resumeIfCompatible(): Boolean`.

- [x] Add a regression case for a detached reading that still has the same verified source geometry and a recycled holder that does not.
- [x] Run the RED test remotely (CI #269).
- [x] Keep the verified overlay reference across temporary detach; clear drawing/spacing, then attempt conditional reinstall on attached paint/layout callbacks. Full unbind still releases it.
- [x] Run initial full tests, Lint and APK CI (#270); independent review identified further cases addressed in Task 3.
- [ ] Leave work unmerged pending the target-phone test of detach/reattach, scrolling, seeking, grouped karaoke and translation.

### Task 3: Independent-review Important fixes

**Files:**
- Test: `app/src/test/java/io/github/proify/lyricon/amprovider/xposed/AppleSoftWrapResumePolicyTest.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleNativeSoftWrapReadingOverlay.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLineWidePronunciationRow.kt`
- Modify: `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/lyrics/AppleLyricsSupplementPronunciationSupport.kt`

**Interfaces:**
- Produces: explicit `RESUME/WAIT/REBIND` outcome, current song/revision/holder generation guard, and a rebind callback protected against stale attachment.

- [x] RED: same line count but changed font/padding/letter-spacing, stale owner, detached reading, and settled-but-invalid geometry must refuse unsafe reuse (CI #271, expected unresolved references).
- [x] Implement: distinguish waiting from invalid, prevent stale owner restoration, and schedule one guarded rebind when the geometry is invalid.
- [ ] Run complete test/Lint/APK CI and separate device verification gate.

Second read-only review found two further rebinding races before final approval:

- A native adapter bind always increments the generation. The **incoming** binding must be authorized before reusing identical existing rows; the **previous** closure is only for asynchronous old-view work. Fixed without forcing a source-only remeasure on every normal bind.
- A settled-invalid painter can have a transient source remeasure. Keep a stale-snapshot flag until the reading is discarded, so the pending-layout early return does not preserve an invalid reading indefinitely. A rebind runs on the next view-post, releases its one-shot flag, and retains bounded delayed retry.
