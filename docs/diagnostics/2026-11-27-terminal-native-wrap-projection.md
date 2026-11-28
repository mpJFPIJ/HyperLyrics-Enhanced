# 2026-11-27 — bounded terminal native-wrap interleaving audit

## What is known

- The user-supplied Apple Music 6.5.3 APK declares karaoke word `CustomTextView`s
  `singleLine=true`, with the main native karaoke Flexbox configured to wrap.
  This is evidence of the **default XML**, not proof that runtime code never
  changes line layout. The user's earlier video cannot provide native child
  bounds, UTF-16 line offsets, or phonetic span provenance.
- `AppleInterleavedReadingPolicy.plan` already uses actual measured Flexbox
  child top/bottom positions, exact source-letter coverage and independently
  proven Hangul sounds. Its old global guard rejected the *entire* row plan if
  **any** nested native word `TextView.Layout` had more than one line.
- The single-TextView inline overlay reserves spacing only when one source
  Flexbox child contains exactly one native source word. With several source
  children, inserting inside a wrapped TextView is **not** a proven physical
  operation. The outer fallback preserves the complete reading and avoids
  invented sound splits, but may visibly collect all pronunciations after
  the last Japanese word or encounter separate host painting constraints.

## The source-level correction (tests authored, not executed)

A narrow, unambiguous partial case does not need an insertion *inside* an
internally wrapped word:

1. Native source measurements are settled and yield two or more Flexbox rows.
2. Exactly the **last** source Flexbox cell has an internal `Layout` line count
   greater than one, and that cell is the **sole** member of the final Flex row.
   It and at least one preceding cell must contain real source letters;
   decorative/punctuation-only rows do not prove a sound boundary.
   The bind also checks every following **non-source native sibling** (for
   example a translation): each must have valid measured bounds starting
   below the last source cell. An overlapping, unmeasured or reverse-ordered
   sibling leaves the entire line on the existing conservative fallback.
3. The existing proof pipeline can assign complete Hangul readings to each
   measured Flexbox row, using raw UTF-16 source identities or independently
   reproducible full-line literal kana. Unverifiable cross-row sound remains
   unsplittable.

Only then may the known earlier Flex rows receive their verified Hangul rows.
The final native cell remains intact and its **entire** verified reading goes
after that cell, as one unit. This avoids moving source text, inventing an
internal source-character breakpoint, splitting Apple word clocks, or claiming
that its two native `Layout` lines each have a separate Hangul slot. The
existing `AppleInterleavedReadingTimingPolicy` uses original native intervals;
whole-line clocks never become synthetic per-row clocks.

Rejected cases stay unchanged:

- Any wrapped native source cell **before** the terminal source cell.
- A wrapped terminal source cell with another Flexbox source peer on its row.
- Zero/unknown native line counts, invalid/reversed/ambiguous measured bounds,
  punctuation-only terminal/earlier rows without a spoken source, incomplete
  raw Japanese–Hangul anchor coverage, and non-additive splits.
- One source Flexbox row, where the existing single-TextView overlay may still
  be the only verified specialized path.

The rootless `linewide_visual_rows_interleaved` diagnostic now carries a
bounded, lyrics-free `terminalWrappedCell=0/1` flag. An interleaved result with
`terminalWrappedCell=1` must be treated as a **partial** layout improvement,
not proof of per-internal-line reading or host clipping safety. The bounded
record occurs on both successful settled ReadingView reuse and completed fresh
insertion, so a one-to-two native line-count change remains eligible for
bounded sampling. A failed addView/rollback does not claim success. Because
common Flexbox rows may consume the ordinary stage's limited samples first,
successful terminal-wrap partial rows additionally emit their own bounded,
lyrics-free `linewide_terminal_wrap_partial` event.

## Remaining device evidence and safety gate

The layout correction has **not** been compiled, unit-tested, built into an
APK, or replayed on Apple Music 6.5.3. That is deliberate: the user requires
one final integrated executable gate, not intermediate Gradle/CI/APK builds.
The added source-level regressions cover literal kana, exact dictionary spans,
preserved original Apple word clocks, final-row peers and earlier wrapped
cells, following non-source native sibling bounds, plus privacy-safe diagnostic
transport. They must run in that gate.

The user's main four-Japanese-then-four-Hangul symptom remains **unresolved**
where a wrapped native cell has peers, an earlier native word wraps, or verified
source sound boundaries are missing. In particular, Apple 6.5.3's single-line
XML makes it unsound to assume the old video followed the internal-wrap branch.
The matching-build rootless `fallbackGate`, `verifiedProjection`, `flexRows`,
`nativeWrappedCells`, alpha/clip traces and original playback position are
still required before implementing an invasive multi-child inline overlay.
