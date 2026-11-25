# Plan: route verified mixed Japanese fragments through native karaoke

Spec: `docs/superpowers/specs/2026-11-25-shared-native-reading-proof-design.md`
Workspace: clean `hle-followup-985pJZUc` worktree.

## Task 1: pure boundary proof and regression coverage

Files: new `root/aitrans/VerifiedJapaneseFragmentReading.kt`; tests in
`AppleWordReadingAlignmentTest.kt` and `AppleNativeReadingProjectionTest.kt`.

1. Add RED tests for `脳|みそ`, `喚|く`, `溺|れ`, full one-cell reading;
   negative two-Kanji `世|界`, digraph `き|ゃ`, and non-Hangul orphan.
2. Run the JVM unit-test task: expect RED for newly supported cases.
3. Extract the single exact-reading resolver and route native word alignment
   and native anchored projector through it. Remove the unconditional
   two-word native renderer restriction only for proven source mapping.
4. Run unit tests: expect GREEN, with prior negative cases still rejected.

## Task 2: unify fallback and native proof

Files: `AppleInterleavedReadingPolicy.kt` plus associated tests.

1. Add an equivalence assertion between visual-row proof and native projection
   for the same fragment list.
2. Delegate measured-row proof to the shared pure resolver.
3. Run the targeted tests and full unit suite, then create commit.

## Task 3: CI, cross-code review and delivery

1. Verify diff and formatting, push to existing Draft PR #20 (do not merge).
2. Run/check Android GitHub Actions tests and APK build; inspect job failures.
3. Run independent whole-branch code review; fix Important findings with tests.
4. Report exactly which failures are covered by proof and which on-device
   visual behaviors remain unverified.

Review focus: context-sensitive particles, kana digraphs, repeated kana,
silent punctuation cells, missing native word spans, single-cell layout,
coexisting source and projected masks, lifecycle on recycled views.
