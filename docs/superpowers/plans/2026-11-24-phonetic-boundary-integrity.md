# Phonetic boundary integrity — implementation plan

> Use the executing-plans and verification-before-completion skills when implementing.

**Goal:** Correct the false Hangul-to-native-karaoke-word assignment without
changing Apple's native Japanese words, clocks or layout.

**Spec:** `docs/superpowers/specs/2026-11-24-phonetic-boundary-integrity-design.md`

## Task 1 — executable regression on the actual defect

- [x] In `AppleNativeReadingProjectionTest.kt`, assert partial or absent
      phonetic proof cannot assign `오보레`/`시니소` across unrelated native
      cells by Japanese character count.
- [x] Assert complete sound/span matches still use original native cells,
      and kana anchors with exactly one residual source cell remain supported.
- [x] Preserve silent-cell and digraph/compound safety coverage.
- [x] Observe a RED behavioral failure with the old implementation in CI or
      another executable Kotlin/JUnit environment.

RED evidence: #279 three failures, #281 two more, #282 four failures
(including the two previously reproduced cases), all at JUnit assertions.

## Task 2 — correction at the projection boundary

- [x] Refactor `AppleNativeReadingProjection` to place only complete sound
      units supported by an independently verified cell boundary or a unique
      residual one-cell gap. Never divide a sound by Japanese character count.
- [x] Reject contradictory span-to-full-line claims; never let an approximate
      diagnostic label disguise a guessed split.
- [x] Integrate with `AppleLyricsSupplementPronunciationSupport` while retaining
      its native word/aligned fallback selection and song/revision scoping.
- [ ] Verify all new and existing phonetic/soft-wrap/timing regressions.

## Task 3 — surrounding code audit and verification

- [x] Inspect timed units, interleaved/soft-wrap geometry, foreground ownership,
      translation, song switch, seek and native auto-scroll for additional
      reproducible defects; fix any proven defects with a failing test first.
- [ ] Run full JVM tests, Android debug/release lint, ABI and signed APK CI;
      verify artifact hash. Report an unavailable local Gradle honestly.
- [ ] Record remaining host/device-only cases and supply a reviewable branch
      and test artifact without merging an unverified Android runtime change.

## Task 4 — review-discovered context-dependent kana clock theft

- [x] Obtain an independent read-only review of the projected native word
      anchors; identify particle `は`/`へ` and cross-cell `こう` false positives.
- [x] Add literal behavioral regressions for `鳥は羽`/`토리와하네`,
      `鳥へ蛇`/`토리에헤비` and `行こう海`/`이코우미` in
      `AppleNativeReadingProjectionTest.kt`.
- [x] Observe all new assertions failing for the expected wrong native-cell
      assignment before writing the implementation: CI #286 (3/3 RED) and
      #287 (5/5 RED, with the verified-span positive still passing).
- [ ] Make automatic kana anchors contingent on independently valid contextual
      pronunciation; preserve complete verified spans and additive kana rows.
- [ ] Verify RED→GREEN and the full Android unit/lint/APK/signature/checksum
      workflow, then restore PR #19's stacked base and document the limits.
