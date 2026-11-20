# HyperLyrics Enhanced — build #234 root-cause ledger

Date: 2026-11-20. This is an evidence ledger, not a claim that every symptom is fixed.

## Evidence inventory

| Evidence | What is verified | Limits |
|---|---|---|
| `1000034138.mp4` | 92.81 s, 720×1568, 4,873 frames, ~52.5 average recorded fps; reported #234. Visible misfocused Korean reading/meaning, cross-cell syllable, visual grouping anomalies. | No audio track, player-position telemetry, source cell identities or GPU frame times. Cannot prove audio clock drift or measure actual rendered-frame jank. |
| `XRecorder_20261119_01.mp4` | 37.9 s older comparison. Visible horizontal reading mask clipping. | Does not identify exact faulty paint operation by itself. |
| `evidence/npatch_1119/app/com.apple.android.music/20261119.log` | Identifies **build 44436352667**, native `lineWide=0` reading with `readingCells=15`, `gradientParents=2`, translation matches of one cell; many captured `activePositions=[]` snapshots. | NOT a #234 runtime log. Cannot attribute a #234 frame to an older trace. |
| Git history through `51cf0af4` and PRs #9/#10 | Proves introduction of experimental native Hangul foreground after earlier mask fixes, and repeated changes to focus selection and view ownership. | A prior CI green proves only its test/lint/build steps, not host video correctness. |

## Actual code/data flow

1. `ApplePlaybackHooks.currentPositionMs()` gets the current Apple Exo player position and has a bounded paused-clock fallback. `AppleLyricsSupplementNativeModelReflection.appleLyricsCurrentPlaybackPositionMs()` passes it unchanged for native lyrics; `AppleLyricsSyncOffset` applies only to supplemental lyrics.
2. `AppleLyricsSupplementPronunciationSupport.hookApplePronunciationWordsGetter` takes native Japanese raw line/word times, chooses official/user/AI Hangul, and retrieves verified source phonetic spans from `AppleNativeOnlineTranslationStore` keyed by song+begin+end+source. The AI transport `AppleMusicSourcePhonetics.attach` uses `AppleWordReadingAlignment` and `AppleNativeReadingProjection`.
3. `AppleWordReadingAlignment.align` distinguishes `Verified`, `Grouped`, and `LineWide`. The caller currently projects `Grouped` as approximate syllable-split native words, even though the source does not contain the invented boundaries. `ApplePronunciationPolicy.verifiedNativeSegments` accepts the artificially populated per-cell strings, and the host creates one pronunciation native word per source word. Unsupported alignments receive one `ReadingView` per native holder via `AppleLineWidePronunciationRow.bind`.
4. The same holder/native translation getter records an `AppleTranslationLineContext` with song, source text, begin/end, translation and revision. Holder binding finds translation TextViews, strips conflicting text color spans and installs `AppleNativeTranslationHighlight.Pulse`, whose one group-level shared timer chooses a focus through `AppleTranslationFocusPolicy`.
5. Japanese source remains Apple's native word renderer and native gradient. Native Hangul is also Apple's native word renderer, but #234 additionally copies it via `AppleNativeHangulWordForeground` outside the parent gradient and on its own periodic invalidation. Fallback Hangul has a different `ReadingView` + `AppleHangulReadingForeground` exclusive-owner path. Translation has its own native-hidden + `AppleKoreanTranslationForeground` path. These are **three different paint and timing paths**, not one synchronized owner.
6. For seeking, Apple owns the tap-to-seek callback. The next `ApplePlaybackHooks` sample is read by the translation focus scheduler, word overlay focus checks and fallback syllable pulse; native source active positions and the blur/follow restoration logic are separately supplied by Apple's adapter. No source shows that these all update atomically on every seek.

## Symptom-to-cause ledger

| ID | Observed or requested | Proven code defect / validated mechanism | Still unproven | Proposed regression |
|---|---|---|---|---|
| A | Japanese and Hangul can disagree even in pause or inactive rows | #234 native Hangul is drawn again outside native gradient, with no per-Japanese-word progress mapping. | Exact #234 per-frame alpha values and whether every observed anomaly used this painter. | Native word paint ownership; host progress remains authoritative. |
| B | Only some translated lines focus | `AppleTranslationFocusPolicy.select` treats an empty active list as an absolute veto, and gives stale host-active rows precedence over valid timed rows. | Which particular #234 frames had empty or stale native sets; binding-context misses. | Empty/stale sets, timestamp boundaries, overlap and seek. |
| C | `溺れ` sound ends in next reading cell | `AppleNativeReadingProjection.project` distributes a verified cross-cell phonetic token proportionally, promoting invented syllable boundaries. | Exact host source cell vector at the recording's 60–62 s. | `溺れ死にそう`, split `駄目`, repeated verified words. |
| D | Japanese rows followed by displaced block of Hangul | Line-wide fallback inserts one full-width reading **after all** source cells; native projected cells follow the host flexbox geometry. | Whether the recording shows one long soft-wrapped logical line or multiple separate logical lines. | Logical group/holder identity; UI multiwrap device replay. |
| E | Hangul/original clipped top/bottom, horizontal split | Host `FullWidthAlphaGradientFlexboxLayout` has native opacity masking; source-relative font sizing and `ReadingView` outer painter address different paths. | Actual #234 glyph bounds/layout and device font metrics; regression after removing redundant native overpaint. | Large fonts, small widths, layout bounds screenshots. |
| F | Korean meaning clipped or double painted | Translation uses a second outer painter and hides native paint only while ready. Double-native children are known possible from Apple's partial translation payload; plugin now requests full binds. | Whether #234 double means duplicate view children, native alpha overwritten, or geometry from a stale painter. | Measured owner counts and same-frame visible layers. |
| G | Click/seek sometimes leaves no highlight | Timing focus can disagree with stale host active set after seek (code-defined mechanism). | Actual seek event/player callback at those #234 instants. | Seek backwards/forwards with stale host sets; device tap replay. |
| H | Apparent sync drift | Independent paint clocks and invented syllable boundaries can **look** offset while native source clock is unchanged. | A real player vs source timestamp drift is not demonstrated by silent video. | Clock discontinuity, source/reading active intervals, seek/pause. |
| I | Reported jank | #234 adds a per-attached-holder scheduler for native word overlay (80 ms focused, 180 ms unfocused) and repeated `Layout.draw` passes, in addition to the shared meaning scheduler and fallback reading scheduler. | No frame time, GC, main-thread trace or before/after profile. | Remove redundant polling/drawing; measure on device. |

## Evidence-led changes prepared for final validation

1. `AppleNativeReadingProjection.project`: a `Grouped` (ambiguous cross-word) alignment no longer becomes fabricated per-cell Hangul. `Verified` alignments retain Apple's real native word clocks. A whole line fallback may still require device layout verification.
2. `AppleTranslationFocusPolicy.select`: validated playback intervals determine the active meaning; stale/empty host active positions cannot veto or move it to a non-playing interval. When the clock is unavailable, only a unique host-native active position is permitted.
3. `AppleTimedPronunciationHighlightPolicy.units`: no Japanese-source-character-to-Hangul-syllable interpolation; use whole verified source phonetic groups with real source word windows, or one coarse genuine native line window. Missing/ambiguous phonetics remain dim rather than claiming exact syllable timing.
4. `AppleLineWidePronunciationRow.fitNativeHangulSize`: drop the second per-holder native Hangul overlay; keep the actual native word `TextView`'s word timing, source-relative font size and font padding. Keep the separate full-line fallback's exclusive paint path.
5. `AppleNativeTranslationHighlightPolicy.shouldRenderTranslationCell`: a positively identified `lyrics_translation_line` can contain a legitimate one-syllable Korean meaning; unknown view IDs still require stricter matching.
6. `AppleLineWidePronunciationRowPolicy.lineWindowForVariant`: discovered another distinct repeat-chorus bug in review—the original holder line window was passed to every repeated timing variant, collapsing later occurrences to the first one's time. Only the first variant now receives that window; repeated occurrences rely on their own real native word clocks.
7. `AppleTimedPronunciationHighlightPolicy.activeVariant`: interval selection now requires a *currently active trusted unit*, instead of treating the unknown gap between partial timed phonetic spans as sung.

These changes address **verified code defects**, but are not equivalent to a successful Apple Music device replay. Removing the redundant overlay specifically reopens the question of native gradient masking and upper/lower glyph clipping on some fonts; that risk must be tested rather than hidden. The native host's own meaning/reading grouping and wrap geometry, clicking/seek callback, audio clock and real frame times are not observable in the supplied silent capture.

## CI regression chronology

| Run | Role | Evidence |
|---|---|---|
| [#235](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44709807816) | Actual pre-fix RED | 3,059 tests, 7 assertion failures in newly introduced mapping/focus tests, 5 skips. |
| [#236](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44711794149) | Initial correction | 3,059 tests, only 1 obsolete projection expectation failed; updated that expectation to line-wide mode. |
| [#237](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44713118814) | Recheck corrected baseline | Canceled automatically on branch update; **not** a passing test run. |
| [#238](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44714448180) | Actual second pre-fix RED | **3,063 tests, 4 expected assertions failed, 5 skipped**: duplicate native reading overlay, single-syllable meaning exclusion, invented `溺れ` sound division, unverified syllable sweep. Other 3,059 tests passed. |

## Required handoff replay

- Install the final CI APK with compatible NPatch and Apple Music; record audio or player-position telemetry alongside the screen.
- Exercise user time markers 8 s, 32–37 s, 38–40 s, 44–47 s, 49–64 s and 83–90 s, plus pause/resume, repeated taps/seeks, song changes, toggles, wrap/small-screen/large-font.
- Capture `native_reading_paint_path`, `translation_cells_bound`, `translation_painter_active/inactive`, `translation_context_missing` with the new build hash (do not reuse the older 1119 log).
- Trace main-thread frame durations and GC in the same run. An APK-only CI result cannot establish absence of stutter, clipping, duplicate draw, or actual sync offset.

## Ground rules for status

- Code evidence alone confirms an architectural failure mode, not necessarily attribution of every video frame.
- "Fixed in source" requires a regression test that failed pre-fix and passed post-fix. "Fixed and verified" also requires an app/device replay for visible rendering or performance problems.
- The old build log is not evidence of new build playback state. Preserve the logs, video and build hashes in the final report.
