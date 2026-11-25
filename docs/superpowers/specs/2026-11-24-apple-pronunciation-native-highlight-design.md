# Apple Music Hangul Reading: Native-Matched Highlight and Layout

## Intent

For Japanese lyrics in Apple Music 6.5.3 on Android API 36, display each Hangul pronunciation directly below its corresponding visible Japanese line and animate its foreground with the **same source word clocks and left-to-right karaoke sweep** as Apple's original. The user has rejected a line-wide, abruptly brightening reading as unnatural. Preserve current-row-only Korean translation focus, pause/seek/scroll correctness and readable full-height glyphs.

## Hard constraints

- Preserve Apple's native Japanese source, click targets, audio/player timing, and karaoke shader. Never rewrite Japanese text or invent timestamps.
- A verified native source-to-sound word mapping should use Apple's own pronunciation word renderer. A grouped or ambiguous Korean sound cannot be split proportionally among native word clocks.
- The line-wide fallback may only animate known source-time intervals. It must never illuminate an unrelated earlier/future lyric or borrow a neighboring word's phonemes.
- A fallback reading must sweep continuously in **pixel space** from left to right, matching source's visible karaoke behavior instead of fading entire Korean syllables uniformly.
- Line breaks must preserve all original and Hangul letters in order and forbid detached Hangul jamo. The target is original→reading→meaning per visual row, including narrow mobile layouts and wrapped native TextViews.
- Layer geometry and font ascent/descent must never allow white strokes to be clipped halfway up or down a glyph; no duplicate foreground owner may paint the same text.
- The system must stay compatible with NPatch and avoid a per-visible-line 60-fps CPU scheduler explosion.

## Approaches considered

1. **Keep syllable-wide color interpolation (rejected).** Lowest risk, but it cannot produce Apple's continuous horizontal fill and reproduces the video's all-at-once/stepped-looking syllables.
2. **Native Apple renderer only (rejected as sole strategy).** Best fidelity for provably aligned word sounds; it would hide valid readings when a Kanji compound or the AI has no sound boundaries. The current repository deliberately avoids unsafe fabricated boundaries.
3. **Verified native words plus native-clock, pixel-progress fallback (chosen).** Reuse Apple's renderer when the sound map is verified. When it is not, preserve the Hangul line and animate only the verifiable portion using a full-glyph, horizontal coverage gradient; never pretend that a whole line is word-synchronized.

## Failure evidence and acceptance criteria

Recording `1000034205.mp4` (173.476 s, 720×1568):

1. ~60 s: four Japanese rows followed by four Hangul rows, including `脳` / `노` in different visual rows. Every **measured** Japanese row must have a directly adjacent Hangul segment, without lost or duplicated sound.
2. ~82 s: orphan Hangul `ㄴ` and displaced `恋煩い` reading. Never split a Hangul syllable or create a stranded jamo; ambiguous source spans must remain intact, and an unsupported narrow layout must fail safely.
3. ~100 s and 164 s: source bright while corresponding reading dim, or vice versa. For known word clocks, pronunciation progress and completed words must track native source progress through short inter-word rests without accidentally brightening another lyric.
4. ~79–87, 112, 130 and 164 s: half-height white highlight masks. The entire ascent/descent must be drawable; clip only to verified actual canvas geometry rather than arbitrary holder rectangles.
5. ~84–88 and ~140 s: unnecessary word/token spacing. Preserve natural reading spacing independent of native source word margins.
6. Pausing, resuming, seeking, RecyclerView recycling, returning from an offscreen position, repeated chorus and lyric toggles must not leak highlight or stale foreground paint.

## Implementation components

- `AppleTimedPronunciationHighlightPolicy`: distinguish strict speaking time from a currently presented line for accumulating completed word highlights; keep repeat variants isolated.
- `AppleReadingGlyphCoveragePolicy` and `AppleLineWidePronunciationRow`: calculate continuous pixel fill from real timestamp progress and measured Hangul glyph bounds; retain native row fade without copying the wrong original word's stale color.
- `AppleInterleavedReadingPolicy` / `AppleNativeSoftWrapReadingOverlay`: map only verified source and reading row boundaries; prevent falls back to a visibly detached group when a host word has internally wrapped.
- `AppleHangulReadingForeground`: coordinate-space clipping and text paint ownership must include the actual glyph metrics.
- `DiagnosticGitHubUploader` / UI probes: record anonymized layout and highlight state alongside service events, tagged by song revision and recording session, to diagnose on-device behavior.
- Regression tests: exact timed gaps, repeated chorus, partial phonetic spans, last syllables, 720px viewport row layouts, invalid/missing native timing, clipping geometry and seek/rebind.

## Delivery and limitations

Ship changes in independently testable stages, not one speculative patch. Source/JVM tests and CI compilation are necessary; an NPatch-installed Apple Music playback recording on the Galaxy Buddy3 remains the final visual gate. Passing JVM tests alone is **not** evidence that the Android rendering issue is completely resolved.
