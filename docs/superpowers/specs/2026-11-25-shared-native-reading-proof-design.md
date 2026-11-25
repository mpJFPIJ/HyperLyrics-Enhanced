# Shared native pronunciation boundary proof

## Root problem

Apple Music already owns original Japanese word clocks and text masks. A verified
Sudachi source token often straddles two Apple native cells (`脳|みそ`, `喚|く`,
`溺|れ`). Although the existing interleaved fallback can prove a boundary from
literal kana and the single residual Kanji segment, the native projection
rejects the same proof. Most lines therefore go through a second, less stable
reading layout and painting path. Diagnostics from the user's song counted
19 line-wide lines versus two aligned lines.

## Decision

Introduce a single fail-closed, pure Japanese-fragment reading proof. It accepts
complete Hangul syllables only; all-kana fragments must independently reproduce
the whole verified sound, or exactly one Kanji-bearing fragment may receive the
residual after the remaining kana fragments are independently proven. A split
containing two Kanji unknowns, a cross-cell kana digraph, missing syllables, or
compatibility jamo remains indivisible. The exact same proof must be used by
source-word alignment, native anchored projection, and measured-row fallback.

Allow one native timed source cell when its complete pronunciation is verified;
reject unspanned unsupported readings just as before. Preserve native timing,
not simulated Hangul phoneme durations. The code may use the host's highlight
mask, but the user's one-to-one word-highlight requirement only holds when the
host supplied corresponding timed word cells.

## Constraints

- No source-count, glyph-width, or syllable-count proportional splits.
- No unverified syllable assignment across two native source cells.
- Keep `Grouped` / line-wide behavior for genuinely unsplittable Kanji words.
- Preserve original text as the source of native word timing and attribution.
- Treat uncomposed Hangul jamo as invalid phonetic output at the shared
  transport validation boundary, not only during native word projection.
- A single orphan `ㄴ` may be composed as the preceding syllable's coda
  only when a complete all-kana line exactly retranscribes to the repaired
  reading. Kana fragments inside mixed Kanji lines do not prove sound position;
  request a corrected model reading instead. Recovered items must store
  sanitized text; all other orphan jamo remain invalid.
- Test the Japanese fragments observed in the video and negative examples.
- Do not assert UI clipping is fixed solely on the strength of JVM tests.

## Rejected alternatives

- Rebuild a third karaoke canvas: duplicates the host's timing/gradient and
  increases RecyclerView lifecycle risk.
- Force all Hangul onto native cells: can borrow incorrect timestamps when
  source boundaries are not proven.
