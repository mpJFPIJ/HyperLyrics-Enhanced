# Correct interlinear Korean phonetics without native karaoke text corruption

## Intent

Fix the phone screenshots showing `다메目`, `다린 リン베이비`, `한파端나쿠`, `와루이`, oversized Japanese glyphs in the Korean reading, spurious word gaps, and misplaced syllables. Preserve an intact Japanese line and place a readable Hangul line immediately beneath it; follow the native lyric's visual activation/fade.

## Root cause

The November 7 grouped native-word alignment puts a full morpheme's Hangul on the first Apple karaoke cell and an empty string on later cells. Apple's own word renderer can display the original Japanese word for an empty replacement. It also measures the reading row using the original Japanese cell geometry, so grouped Hangul overflows and wraps unpredictably. Validating the concatenated sounds is insufficient to validate a *visual* native word map.

## Approaches

1. **Safe hybrid (chosen given repeated requests to implement directly):** use native word rendering only if every visible native cell has a verified, nonblank reading. For all split morphemes or partial alignment, use an independent full-width Hangul reading row. Insert it immediately after the original Japanese cells and before any subsequent translated content; bind it only to an exact observed source. Mirror the lyric's visual brightening/fading without inventing karaoke syllable times. This is the smallest reliable change.
2. Build native Japanese `LyricsWord` clones with new Hangul text/timing. Rejected: JavaCPP native object ownership and line timings would be unsafe, especially on NPatch.
3. Keep putting grouped Hangul into the first native cell. Rejected by the screenshots.

## Constraints

- Never return Japanese glyphs as Hangul pronunciation, never put a blank Hangul reading in a native word cell, and never split unverified sound by width.
- Original source words, timestamps, translation and official Apple pronunciation remain unchanged.
- Leave `main` untouched until Galaxy Buddy3 + Apple Music 6.5.3 device verification.
- Unverified or missing AI pronunciation may use a complete linewide reading if previously sanitized, but not a fabricated timed syllable.
- Text remains readable at narrow widths with no clipping; recycled holders never reuse the wrong line.

## Verification

- Pure tests for source split (`駄/目`, `ダー/リン`, `半/端`, `悪/い`) choosing full-width, punctuation-only cells choosing full-width, and wholly verified nonblank words retaining native timing.
- Pure tests for insertion after the original source prefix, not after unrelated translation words, with punctuation and source mismatch.
- CI full JUnit + lint + signed APK + ABI checks, then phone screenshots (word highlight approximate for fullwidth groups; no claim of exact per-syllable timing).
