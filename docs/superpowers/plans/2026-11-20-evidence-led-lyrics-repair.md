# Evidence-led lyrics repair implementation plan

**Spec:** `docs/superpowers/specs/2026-11-20-evidence-led-lyrics-repair.md`
**Baseline:** #234-equivalent local `51cf0af4`, branched as `fix/lyrics-evidence-root-causes-20261120`.
**Constraints:** TDD first; run the complete JVM suite, lint and APK CI when available. Report missing Android device evidence without claiming full completion.

## Task 1 — Verified Japanese-to-Hangul boundaries

**Interfaces:** `AppleWordReadingAlignment.align` → `AppleNativeReadingProjection.project` → `AppleMusicSourcePhonetics` and `AppleLyricsSupplementPronunciationSupport` → `ApplePronunciationPolicy.presentationMode` → `ReadingView`.

1. RED: change the grouped-source projection test to assert rejection of fabricated native cell boundaries; add `溺れ` and repeat-token boundary cases. Run the targeted JVM test and observe the intended assertion failure.
2. GREEN: `project()` accepts only `WordAlignmentResult.Verified`; preserve the complete reading in line-wide fallback. Run the targeted test and full relevant tests.
3. Check that source phonetics transport and native fallback still send complete Hangul text and no empty Japanese glyph cell. Commit tested change.

## Task 2 — Timing first, host corroboration second

**Interfaces:** playback clock → `AppleTranslationFocusPolicy.select` → visible translation pulses. Also `AppleLyricsBlurHooks.currentNativeFocusedAdapterPosition` for native pronunciation focus.

1. RED: unit cases for native empty active sets during a valid source window, stale future active row, overlap, gaps, pause (same position) and seek backwards.
2. GREEN: select by valid timed interval first; native active positions choose among genuinely overlapping intervals. If clock is unknown, only a unique native-active row may be selected. Re-run targeted and full suites; commit.

## Task 3 — Preserve real source phonetic-group timing

**Interfaces:** native word/line times + verified `ApplePronunciationSourceToken` → `AppleTimedPronunciationHighlightPolicy.units` → `ReadingView` span styles and active-variant selection.

1. RED: a complete token `溺れ` spanning native cells must get one coherent timing unit; a complete line without phonetic anchors must get one honest line-clock unit, never one invented unit per Hangul syllable.
2. GREEN: map only exact validated source token boundaries to genuine native word-window bounds. Missing or ambiguous source alignments fall back to one whole-line window when line timing is real, or stay dim if even that interval is not known. Partial unambiguous anchors may retain their real word clocks but the missing reading must not receive synthetic phoneme timestamps.
3. Update old tests that assert guessed proportional syllable timings. Run the full JVM suite and check no out-of-range index or repeated-chorus drift. Commit verified change.

## Task 4 — Remove unverified native-word overpainting

**Interfaces:** `AppleLineWidePronunciationRow.fitNativeHangulSize` → native Hangul TextViews, `AppleNativeHangulWordOverlayManager` → holder overlay. Retain `ReadingView` fallback.

1. RED: structural regression test that the native word renderer cannot have a second independently scheduled overlay manager. The fallback `ReadingView` has its own exclusive paint ownership and remains available.
2. GREEN: remove the experimental native-word foreground and its per-holder scheduler; keep source-relative font sizing and full font padding, native host timing, fallback outer painter and visibility toggles. Do not replace with a timer.
3. Document expected visual trade-off; #234 real device clipping must be retested, not automatically classified fixed. Commit tests + source.

## Task 5 — Translation binding and remaining runtime evidence

**Interfaces:** translation TextView bind / `Pulse` lifecycle / `AppleKoreanTranslationForeground`; row refresh; seek/recenter; runtime probes.

1. RED: recognized single-Hangul-letter translation cell must still be focus-eligible, while unknown-ID one-letter source cells are ignored.
2. GREEN: narrow the one-letter exception to verified native translation IDs. Audit owner correctness and duplicate native translation cells; add bounded diagnostic facts only where they discriminate causes without content collection.
3. Where another reproducible code defect is found, write RED regression, GREEN patch, run complete suite.
4. Compare two videos and the older log without conflating different builds. Verify no overclaim about player time or measured frames.

## Task 6 — Verification and handoff

1. Run all unit tests, lint, debug APK build (GitHub Actions fallback if Gradle distribution unavailable locally). Inspect failures before claiming success.
2. Review whole diff against spec, test exact bounds/repeated lines and host/version guards.
3. Produce evidence table A–I: confirmed / fixed in source / CI tested / device unverified / unresolved, with a CI artifact URL if produced.
