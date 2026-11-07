# Implementation steps

1. Add regression tests for native child insertion barriers, source-span timing, syllable progress and invalid timing.
2. Implement a pure Android-independent highlight mapper, validated by the tests; do not alter Apple model objects.
3. Record read-only Apple main word times and local AI phonetic spans in the song/revision scoped line-wide payload; route Korean supplemental readings consistently to the safe full-width row.
4. Replace ReadingView's all-at-once color pump with playback-positioned per-syllable `CharacterStyle` and preserve native parent opacity; install directly between original and translation.
5. Improve binding diagnostics and source-identity recovery without assigning readings to ambiguous lines.
6. Run available local checks, push to the existing draft PR, provide the GitHub Actions build link immediately, inspect CI and fix failures; keep main intact until phone QA.
