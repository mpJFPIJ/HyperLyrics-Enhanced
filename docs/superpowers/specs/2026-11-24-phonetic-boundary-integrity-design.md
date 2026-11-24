# Native Japanese ↔ Hangul boundary integrity — design

## Observed code defect

`AppleNativeReadingProjection.projectForNativeKaraoke` may accept a complete
Hangul line without verified source phonetics and call `projectApproximate`.
The latter divides syllables in proportion to the number of Japanese source
characters in each native word. A full-line reading is **not** a proof that
those ratios identify the word boundary. The result is then accepted by
`ApplePronunciationPolicy.verifiedNativeSegments` on source and concatenated
text alone, and incorrectly inherits Apple's authentic but **different-word**
karaoke clocks. `projectAnchored` makes the same error inside source gaps or
a dictionary token crossing multiple native cells.

## Contract

1. Apple's Japanese glyphs, timestamps, taps and auto-scroll remain authoritative.
   Do not invent Hangul syllable boundaries to populate an Apple word cell.
2. A native Hangul word may own sound only when its source↔sound boundary is
   established by a complete verified span, an independently transcribed
   whole kana word matching the selected line, or a **single remaining native
   cell** containing actual source letters between sound anchors. A one-cell
   Kanji residual may contain unverified sounds; mark the result approximate,
   but no sound is split among clocks. Punctuation-only cells are silent;
   an independently readable literal-kana residual must match its own sound.
3. A verified compound crossing multiple native cells remains intact. If each
   cell is independently verified to rejoin exactly the original sound, fine
   spans can be generated upstream; otherwise fall back to the measured
   line-wide/interleaved renderer, with no invented token split.
4. An absent, contradictory, incomplete or stale source span must not be
   promoted to timed native words. A punctuation-only span may not carry
   Hangul sounds. Do not silently discard a contradictory span in favor of
   position-weighted reconstruction.
5. Native all-kana cells can be used if their independently converted sounds
   concatenate **exactly** to the selected full reading; a genuinely silent
   punctuation or long-vowel cell gets an invisible placeholder only with
    contextual whole-line evidence. Digraph boundaries that change sounds
    must fail closed. In a mixed Japanese line, a uniquely occurring Hangul
    substring is **not** itself an independent kana anchor: exclude unverified
    hiragana words containing potentially grammatical `は` or `へ`, and
    reject anchors whose sound changes when adjacent kana on either side
    are transcribed as one contiguous run (including `こう`'s silent `う`,
    small kana, sokuon and nasal coupling). Preserve fully verified source
    spans for these cases.
6. Keep the existing verified flexbox and native-TextView soft-wrap projection,
   exclusive paint owner, cached song/revision checks and seek/follow guards.
   The fallback must be observable in a bounded diagnostic, not hidden.

## Approaches

- **Selected — proof-carrying boundary projection:** retain the native word
  renderer for complete source spans, additive whole kana cells and uniquely
  anchored one-cell gaps. Kana anchors must also pass contextual additivity;
  exclude particles with alternate grammatical readings. Reject all other
  mappings to the existing fallback.
  This removes the manufactured cross-cell pronunciation bug while keeping
  verified multi-row and word highlight behavior.
- Whole-line proportional mapping: visually follows native flexbox but assigns
  unproven sound to clocks, violating the user's 1:1 requirement.
- Full replacement renderer: might control wrapping, but loses Apple's shader,
  click targets and timing and expands host compatibility risk. Not selected.

## Residual evidence requirements

Exact phonetics for an unknown Kanji compound split across two source rows
cannot be derived from a single whole-compound reading. The application must
obtain independent dictionary/AI phonetic boundaries before it can promise
1:1 row syllables; no algorithm can infer them solely from the strings and
cell widths. Unknown device font clipping, holder-layer duplicates, exact
seek timing and frame jank require Galaxy Buddy3 / Android 16 / Apple Music
6.5.3 / NPatch replay with contemporaneous geometry, timing and frame traces.
Passing JVM tests and Android build is necessary, not that device proof.
