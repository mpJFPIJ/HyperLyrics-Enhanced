# Phonetic boundary integrity — implementation plan

> Use the executing-plans and verification-before-completion skills when implementing.

**Goal:** Correct the false Hangul-to-native-karaoke-word assignment without
changing Apple's native Japanese words, clocks or layout.

**Spec:** `docs/superpowers/specs/2026-11-24-phonetic-boundary-integrity-design.md`

## Task 1 — executable regression on the actual defect

- [ ] In `AppleNativeReadingProjectionTest.kt`, assert partial or absent
      phonetic proof cannot assign `오보레`/`시니소` across unrelated native
      cells by Japanese character count.
- [ ] Assert complete sound/span matches still use original native cells,
      and kana anchors with exactly one residual source cell remain supported.
- [ ] Preserve silent-cell and digraph/compound safety coverage.
- [ ] Observe a RED behavioral failure with the old implementation in CI or
      another executable Kotlin/JUnit environment.

## Task 2 — correction at the projection boundary

- [ ] Refactor `AppleNativeReadingProjection` to place only complete sound
      units supported by an independently verified cell boundary or a unique
      residual one-cell gap. Never divide a sound by Japanese character count.
- [ ] Reject contradictory span-to-full-line claims; never let an approximate
      diagnostic label disguise a guessed split.
- [ ] Integrate with `AppleLyricsSupplementPronunciationSupport` while retaining
      its native word/aligned fallback selection and song/revision scoping.
- [ ] Verify all new and existing phonetic/soft-wrap/timing regressions.

## Task 3 — surrounding code audit and verification

- [ ] Inspect timed units, interleaved/soft-wrap geometry, foreground ownership,
      translation, song switch, seek and native auto-scroll for additional
      reproducible defects; fix any proven defects with a failing test first.
- [ ] Run full JVM tests, Android debug/release lint, ABI and signed APK CI;
      verify artifact hash. Report an unavailable local Gradle honestly.
- [ ] Record remaining host/device-only cases and supply a reviewable branch
      and test artifact without merging an unverified Android runtime change.
