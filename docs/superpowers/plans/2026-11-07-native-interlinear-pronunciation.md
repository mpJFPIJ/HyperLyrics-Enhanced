# Native interlinear pronunciation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Place Hangul phonetics directly beneath their Japanese lyrics with native Apple Music karaoke highlighting and reduce missing readings.

**Architecture:** Source-offset phonetic spans are produced in the app process and transported unchanged to the Apple hook. The hook re-aligns them against the actual native lyric word vector, then uses the existing timed native pronunciation renderer. A verified source-grouping fallback avoids fabricated word/syllable boundaries.

**Tech Stack:** Kotlin, Android Xposed/NPatch, Sudachi, JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-07-native-interlinear-pronunciation-design.md`

## Global Constraints

- Original Apple LyricsWord instances and times are immutable.
- No standalone full-width pronunciation row after translation.
- Preserve Gemini translation, toggles, existing API providers and Apple Music 6.5.3 behavior.
- Do not merge experimental PR into main before phone validation.

## Review Focus

- Token `駄目` split as `駄` and `目`: all sound present exactly once, no invented syllable boundary.
- Normal `脳/みそ/の/中/から`: proper source-relative grouping with identical aggregate sound.
- Mismatched source or invalid UTF-16 offsets: no falsely verified native mapping.
- One Sudachi token failure: retain existing valid Hangul fallback, never suppress translation.
- Recycled Apple ViewHolder: no stale linewide TextView from earlier implementation.

---

### Task 1: Source-aware native alignment

**Files:** `AppleWordReadingAlignment.kt`, `AppleWordReadingAlignmentTest.kt`.

- [ ] Write regression tests for cross-boundary grouping, source rejection and phonetic sound preservation.
- [ ] Verify tests fail on previous strict aligner.
- [ ] Implement `WordAlignmentResult.Grouped`, preserving the exact span reading on the first overlapping native word.
- [ ] Verify source offset and complete-reading tests.

### Task 2: Transport and bind using actual native words

**Files:** `AppleNativeOnlineTranslationStore.kt`, `AppleLyricsSupplementPronunciationSupport.kt`, `AppleMusicSourcePhonetics.kt`, policy tests.

- [ ] Write tests for retained source spans and same-sound native display selection.
- [ ] Verify the tests fail without new behavior.
- [ ] Pass source spans to the hook; re-align to native word text at rendering, reuse Apple's native timed word cells for all Hangul readings. Remove the line-wide append path.
- [ ] Verify regression tests and unchanged source-selection semantics.

### Task 3: Missing-line fallback

**Files:** `AppleMusicSourcePhonetics.kt`, corresponding unit tests.

- [ ] Write tests showing one failed local token with a valid existing Hangul reading no longer blanks the entire line.
- [ ] Verify red then implement conservative sanitized fallback with explicit status, and verify green.

### Task 4: CI and handoff

- [ ] Inspect diff and run available full unit tests and build checks.
- [ ] Push only to the existing experimental PR and check Github Actions green before sharing an APK.
- [ ] Request screenshot/device verification of native lyric highlighting; leave main untouched.
