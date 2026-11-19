# Native Hangul pronunciation foreground — investigation and patch

## Confirmed evidence

- The November 19 NPatch trace on Apple Music 6.5.3 includes `lineWide=0`, `readingCells=15`, `gradientParents=2`, `spannedTexts=26`, `paintShaders=0`. This is a native word-aligned pronunciation row, not the injected `ReadingView` repaired in build #232.
- The IRIS OUT video shows a horizontal white/grey boundary through Hangul glyphs. The screenshot alone cannot distinguish a native parent alpha gradient from a TextView glyph clip, but both can affect a native word row.
- `AppleLineWidePronunciationRow.fitNativeHangulSize` currently adjusts native Hangul font size and padding; it does not bypass the parent gradient for those cells. The native Japanese words and Korean translation must remain untouched.

## Design

1. Only native Hangul `TextView`s verified under the native `pronunciation` branch are repaint candidates; exclude the source and translation, `ReadingView`, and unmeasured/detached or changed-text cells.
2. Keep Apple's native pronunciation TextViews intact for layout, timing, `TextPaint` shaders, `Spanned` word styles and per-word alpha. Place one `Drawable` on the *outer lyric holder overlay* and repaint the same `Layout`s at their measured coordinates, clipped only to the holder; no duplicate physical View and no mutation to native Japanese.
3. The original native words remain as a safety fallback. Repaint **only for the one verified currently focused native adapter position**: a single host-active row (unless its known bound time window has ended), or a position resolved by an actual bound line time window when native active rows overlap. Unknown current position and all off-current rows retain Apple's original gradient, avoiding false bright highlights. Suppress this extra native overlay entirely when the injected `ReadingView` is already shown or the user's pronunciation toggle is off.
4. Draw only when measured, bound and visible. Restore original TextPaint alpha/color/shader immediately after each extra drawing pass. Remove the drawable on detach, restore it on reattach, and throttle noncurrent/offscreen checks. Use a weak session registry and log a bounded diagnostic. Because source and overlay may briefly overdraw identical glyphs, device validation must specifically check weight, color, and timing; do not declare visual success from unit tests.
5. Do not replace Apple's native word clocks with an invented syllable timeline or change the `lineWide` branching policy.

## Verification

- First write RED unit tests for candidate ownership, view state and native opacity multiplication.
- Implement the minimal foreground, fail closed on unfamiliar or invalid rows, and run full GitHub CI to build a separately installable diagnostic APK.
- The phone replay of IRIS OUT must compare top/bottom glyphs, syllable/word highlighting, scroll timing, and frame pacing with build #232. Roll back to #232 if a duplicate glyph or jank regression appears.
