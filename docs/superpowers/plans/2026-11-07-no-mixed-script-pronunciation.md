# Safe interlinear pronunciation repair implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate mixed Japanese glyphs, overlapping Hangul karaoke cells and misplaced readings in all three IRIS OUT screenshots.

**Architecture:** Fail closed on incomplete native syllable coverage. Re-enable the existing independent reading row for such lines, but place it immediately after the verified original Japanese cell prefix and visually synchronize it with the native source text instead of appending it after unrelated text.

**Tech Stack:** Kotlin, Xposed, Android native Views, JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-07-no-mixed-script-pronunciation-design.md`

## Global Constraints

- Apple Music 6.5.3 and NPatch Local remain supported.
- No fabricated time ranges, source-mismatch rendering, or raw source Japanese characters in Korean rows.
- Leave existing Gemini translations and app settings unchanged.
- Share the new Actions run link as soon as the commit triggers CI; no need to await APK.
- Preserve draft PR #5 and do not merge main.

## Review Focus

- Source spans crossing two Apple native word cells.
- Punctuation-only Apple cells and isolated `ー`/`・` markers.
- Line source duplicated in another holder or switched mid-scroll.
- The row inserted relative to original Japanese vs translated content.
- Narrow phone width, highlight/fade of original source vs injected reading.

### Task 1: Refuse unsafe native cell mappings

**Files:** `ApplePronunciationPolicy.kt`, `AppleWordReadingAlignmentTest.kt`, `ApplePronunciationPolicyTest.kt`, `AppleLyricsSupplementPronunciationSupport.kt`.

**Interfaces:** `verifiedNativeSegments(pronunciation, nativeWords, alignedWords): List<String>?`; grouped and empty-cell inputs return null. The hook calls `rememberLineWideApplePronunciation` when the presentation isn't provably safe.

- [ ] Write tests for split words, punctuation-only empty cells and fully covered normal words.
- [ ] Verify tests fail before implementation (or record blocked local Gradle and use remote CI).
- [ ] Reject incomplete mapping and register linewide fallback, without reusing the main-word render plan.
- [ ] Verify the focused tests and full suite.
- [ ] Commit the standalone change.

### Task 2: Source-first row placement and brightness

**Files:** `AppleLineWidePronunciationRow.kt`, `AppleLineWidePronunciationRowPolicyTest.kt`, `AppleLyricsSupplementPronunciationSupport.kt`.

**Interfaces:** `insertionIndex(sourceText, directChildSourceTexts): Int?`; null rejects wrong-holder/ambiguous source. An attached reading view lives after the verified original source cells and copies source text's current color/opacity.

- [ ] Write tests for split Japanese cell prefix, extra translation child, punctuation and wrong source.
- [ ] Verify red (or record local build blocker).
- [ ] Insert the reading at verified index, synchronize style with native source and clear on holder reuse.
- [ ] Run tests, lint and code review.
- [ ] Push to draft PR #5 and immediately give the CI run link.
