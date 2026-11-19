# Native interlinear Apple Music phonetics

## Intent

Restore the previous Apple Music visual hierarchy: each Hangul reading sits immediately under its matching Japanese karaoke text, and the reading follows Apple's existing playback highlight rather than appearing after the translation. Address intermittent entirely absent readings without inventing Japanese-to-Korean sounds.

## Constraints

- Preserve Apple's original word objects, timeline, Japanese typography, translation, NPatch compatibility and other UI functionality.
- Never evenly distribute Hangul syllables by word width to claim a verified pronunciation.
- A full-line phonetic fallback after the translation is unacceptable.
- Korean translation quality and preferences are unchanged.

## Approaches

1. **Native word rendering with source-anchored spans (chosen):** transport original source offsets from the offline reading engine and re-align against the *actual Apple word vector at bind time*, then reuse Apple's native pronunciation word rendering/animation. A morpheme split by Apple may be grouped on its first source word when safe word-by-word mapping is not possible. Smallest impact on highlighting and UI.
2. Overlay separately animated per-word TextViews: more control, but duplicating Apple's animation, word clipping and recycling is fragile.
3. Full-width line beneath the lyric: readable, but cannot satisfy requested word association or native highlight and is rejected.

## Behavior

- Match each source-reading span's original UTF-16 indices to real native word ranges; reject invalid/mismatched source and sound.
- A span crossing native word boundaries retains its whole, verified pronunciation once, associated with its first source word; never artificially split it into syllables.
- Render native pronunciation vector for complete aligned and grouped readings, inheriting Apple's animation. Do not inject the previous line-wide full-width row.
- When local Sudachi cannot resolve a line, preserve an existing *sanitized* Hangul pronunciation instead of unconditionally deleting it, with a diagnostic status distinguishing unverified fallback.
- Emit diagnostic counts so missing phonetics can be traced without additional NPatch log requests.

## Verification

Regression tests for `駄目` split native cells, `脳みその中から`, kana-only phrases, invalid offsets, incomplete local analysis with and without a Hangul fallback, and native payload roundtrip. CI unit tests, lint and APK build, followed by user device test for actual Apple karaoke highlights.
