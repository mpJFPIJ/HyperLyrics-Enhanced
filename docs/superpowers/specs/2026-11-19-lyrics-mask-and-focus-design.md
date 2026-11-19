# Galaxy Buddy3 lyric mask and focus repair

## Goal and evidence

User-tested build #227 still has split Korean pronunciation, stale/dual native highlights, inconsistent Korean meaning focus, drifting pronunciation timing, and perceived jank. `XRecorder_20261119_01.mp4` is a 37.9-second 720x1568 recording of IRIS OUT. At about 2 seconds, the Hangul reading's white/gray boundary runs horizontally through glyphs; at 26 seconds two nonadjacent source/readings are bright. At about 31 seconds an apparently active source lyric has a muted Korean meaning. These are video observations; actual host clock, adapter active positions and frame times were not logged in this recording.

The implementation currently inserts `ReadingView` directly into Apple Music 6.5.3's `FullWidthAlphaGradientFlexboxLayout`, which draws its own gradient over all children. It already uses an *outer holder overlay* to bypass that same mask for Korean meaning. It also polls every translation holder separately, selecting meaning focus solely from its remembered line timestamps instead of the native active adapter positions. Source-native karaoke must retain Apple Music's timing/word objects.

## Alternatives

1. **Selected — retain host geometry, move supplemental paint ownership outside the mask.** Keep the measured reading TextView inside the flexbox for layout height, draw its existing syllable-styled Layout once in the outer holder ViewOverlay, hide only the native masked reading paint, and dispose the painter with the holder. Use host active adapter positions as the primary translation row candidate set, with the line clock as tie-breaker. Coalesce translation scans into one scheduler. Keep original Japanese drawing untouched. Small, reversible changes.
2. Change only TextView padding and blur shader. Low risk but cannot bypass the demonstrable *parent* gradient; already failed on #227.
3. Replace Apple's lyric RecyclerView with a fully custom renderer. Could enforce every pixel but risks source karaoke, scrolling, NPatch, settings and accessibility. Not justified.

## Required behavior

- Original Japanese line and native per-word animation remain unchanged.
- Korean pronunciation stays immediately under its source, retains its measured height, and no glyph is split by the native parent's horizontal alpha gradient. The overlay never doubles the reading and is removed when the holder detaches or is rebound.
- Pronunciation syllables only brighten within their own validated song/line timing. No stale highlight after seek, pause or recycled-holder reassignment.
- Korean meaning is active only for the one native-active corresponding row (clock breaks ties), otherwise muted; missing timing/position must not promote an unrelated row. Wrapped meaning has one paint owner.
- Translation scheduler updates each visible presentation group once per tick; no redundant per-holder `rows x rows` rescans or unnecessary native text writes.
- Preserve NPatch, Apple Music 6.5.3, blur settings, word timings, font preferences and signed update APK compatibility.
- No main merge. Keep #227 available as the known-good build baseline.

## Verification

- Test the pure focus decision and the one-overlay rendering state machine first against #227, observe RED then GREEN.
- Test scheduler coalescing and reading timing behavior with controlled inputs.
- Run the complete Android unit suite, Lint, debug/release builds and APK signing/roundtrip in CI.
- CI cannot establish device visual success. User must retry the IRIS OUT sequence on Galaxy Buddy3 and share new NPatch/HyperLyrics logs if stale original text or jank persists.
