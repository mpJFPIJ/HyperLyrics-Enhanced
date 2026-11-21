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
| [#239](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44716655672) | Candidate GREEN | JVM tests, debug/release lint and signed Android APK build passed. This is **not** native-device A–I validation. |
| [#240](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44740919918) | Paused-seek RED | 3,064 JVM tests, **one intentional `AssertionError`** in `explicitZeroSeekWithUnreadableClockMustNotReviveThePreviousLyric`, five previously skipped; reproduced on the original clock selector before any seek fix. |
| [#241](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44743186542) | First paused-seek revision | JVM tests and Debug/Release lint passed. Workflow was **canceled by a newer push during the APK build**; this is not a green full build or host-device validation. The subsequent review found two further defects below. |
| [#242](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44746171892) | Clock-event-gate RED | Failed in `:app:compileDebugUnitTestKotlin` with expected `Unresolved reference 'ApplePlaybackClockEventGate'` in the new delayed-read/seek-order tests. **This proves the guard was absent**, but unlike #240 this is a compile-level RED, not a behavioral assertion on the old implementation. |
| [#243](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44748049375) | Integrated clock-event GREEN | **3,071 JVM tests: 3,066 passed, 0 failed, 5 skipped**, Debug/Release lint, ABI, signed APK build and uploaded-APK byte-for-byte/hash verification all passed. This does not verify host-device lyric rendering. |
| [#244](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44755693987) | Pending-meaning row ownership RED | Failed in `:app:compileDebugUnitTestKotlin`: expected `Unresolved reference 'mayKeepPendingPainter'` in the new same-song holder-recycle cases. This is an absent-policy compile RED, not proof of the specific #234 double-painted frame. |
| [#245](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44757886907) | First pending-owner correction | JVM tests, Debug/Release lint, ABI, signed APK generation, signature and byte-for-byte verification passed. **Not a final candidate**: the subsequent independent review identified two remaining fallback/retry ownership paths. |
| [#246](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44760441763) | Position-scoped cache and fresh-retry correction | Introduces exact-position source fallback, full native source-cell matching and fresh-source retry; subsequently reviewed for adapter-instance identity, optional Traditional→Simplified display and opaque-source-cell compatibility. This run is **not** the final candidate even if its CI passes. |
| [#247](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44762661986) | Adapter identity and conversion correction | Adds weak exact-adapter occurrence ownership and pure cache tests. The following independent review found that fallback retries can still mutate trusted getter provenance and that whole-line phrase conversion differs from real native per-word transforms. **Superseded pending repair**; do not use for device verification. |
| [#248](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44764067220) | Getter-provenance and split-phrase candidate | Re-evaluates cached fallbacks on each retry without claiming a getter, defers on contradictory source cells, and reads verified real native word boundaries before conversion. The next review found an additional partial-native-meaning selection path; **not the final candidate**. |

## Continued seek investigation — source evidence, not a device claim

The source review after #239 identifies a **distinct, testable paused-seek clock defect** relevant to G/H:

1. `ApplePlaybackHooks.installExoMediaPlayer` records an accepted Exo `seekToPosition` only in `lastExplicitSeekAtMs`/`lastExplicitSeekPosition`; it does **not** update the paused fallback clock. `resumeCoroutineTask` refreshes `lastTimingSamplePosition` only while the timeline is advancing, so a paused seek can leave the previous location as the only remembered clock sample.
2. `ApplePlaybackHooks.currentPositionMs` passes `explicitSeekToZero` for just 2,500 ms after the seek. After expiration, a legitimate `currentMs=0` with older `lastValidMs>0` is classified as an unreadable paused player and returns the **previous** lyric position. The special case was time-limited rather than tied to the clock event's actual provenance.
3. Even **inside** that interval, `ApplePausedPlaybackClockPolicy.select` returns `lastValidMs` immediately when `currentMs=null && !timelineAdvancing`, without checking `explicitSeekToZero`. Thus a paused return-to-start with temporarily unavailable getter deterministically revives the previous lyric.
4. The existing `ApplePausedPlaybackClockPolicyTest` covered `currentMs=0, explicitSeekToZero=true` but not the null-current boundary or post-expiration case. Explicit seek-to-nonzero with transient `currentMs=0` also lacked an event-scoped fallback test.

These defects are confirmed by the control flow and reproducible unit-level inputs; the #234 silent footage **cannot** establish that a specific recorded seek exhibited them. The correct time provenance must distinguish the latest actual player sample from an accepted seek target, be scoped to the same Exo player and song, and prefer a later valid player observation. Arbitrary audio timing offsets or guessed syllable times are inappropriate. The paused MediaSession lyric anchor must also be refreshed when a valid seek occurs; the old code only published this on pause/play state transition. Retest on-device before marking the visible seeking/highlight bug solved.

### Follow-up review of the first paused-seek correction

An independent source review of the #241 candidate identified two further deterministic failure paths and one event-ordering hazard:

1. In `ApplePlaybackHooks`, a newly accepted seek while paused was sent via `publishPlaybackAnchor` only. But `AppleDirectPlayer.setPlaybackState(PlaybackState)` forwards **only** the playing boolean to its lyric bridge; its `seekTo(position)` method is what calls `IAppleMusicLyricBridge.onSeekTo`. `CompositeRemotePlayer.seekTo` also relays explicit seek semantics to the Central provider. Thus a paused `PlaybackState` anchor is **not equivalent** to an explicit seek for either surface. The correction sends `remotePlayer.seekTo(position)` for every accepted native seek, whether the player is advancing or paused.
2. The polling job ran `readPosition()` on a background dispatcher and published its result after the getter returned. `Job.cancel()` does not interrupt a synchronous, already-running getter, and the old code never checked whether a seek, pause, source switch or song switch occurred *during* that read. A late pre-seek read could therefore overwrite a newer seek in the bridge. A generation ticket now precedes each getter read; stale tickets and source/song mismatch discard that result. Remote position publications and explicit seek publications share a serialized clock gate so an earlier delivery cannot arrive after the accepted seek.
3. Separate volatile fields for measured value, song and timestamp could be observed in mixed combinations, and `elapsedRealtime()` millisecond ties were an ambiguous ordering signal. An immutable `MeasuredClockSample` now carries the position, player identity, song, time and strictly monotonic event sequence. An explicit seek retains its separate provenance and sequence. The paused selector chooses by event order, not wall-clock tolerance.

These are verified **source control-flow defects**, not proof that any particular frame in #234 suffered them. The new generation and ordering tests exercise delayed getter completion, invalidation and overlapping bridge dispatch; only a subsequent full CI and real device replay can validate their integrated behavior. Serializing remote lyric bridge calls also has a bounded IPC-performance risk requiring measurement on Apple Music hardware.

### Same-song recycled holder can retain an old translation foreground

The follow-up source audit found another independently testable ownership error in `AppleNativeTranslationHighlight.bind`:

1. `beginHolderBinding` invalidates the prior holder-specific translation context, yet keeps the existing `Pulse` to avoid a flash if the bind is genuinely redundant.
2. When the next native translation getter has not completed (`ctx == null`), the old `Pulse.isSafeFor` tested only song ID, translation revision and whether the previous TextView text object remained unchanged. It did **not** check the actual native adapter position or new source cells. Those identity inputs are available in `AppleLyricsBindingDiagnosticContext.position` and `observedSourceCells`. Apple can reuse the same holder for a *different lyric within the same song/revision* before new translated text arrives. Therefore the old foreground could remain attached to the wrong row despite all previous checks passing.
3. If a later retry finally observed the new source, the old `stopOldPulse` refused to stop the painter when `retry > 0`, prolonging the mismatch. A source/adapter mismatch now forces detachment even during a delayed retry.

The repair retains the old foreground only when the native adapter position is known and unchanged and any observed source cells agree with the old Japanese source. Unknown/mismatched identity fails closed; an unchanged row can still reuse its painter across redundant binds. A bounded `translation_pending_rebind_rejected` trace records non-text identity facts. This prevents one **verified code-level stale-owner path**, but does not establish that this was the sole cause of double translation in the silent #234 video. Actual TextView geometry, external host alpha animation and device frame times remain unmeasured.

### Independent review of #245 — two additional ownership paths

The final review traced two additional code-level errors **after** the first same-song painter guard. The #245 green CI did not cover them:

1. **Source-only fallback bypassed the position guard.** When the native holder switched from adapter row 12 to 13 while displaying row 12's cells, `bind` could pick the one cached old source text from `bySource` *before* checking the pending painter. It would reject pulse reuse on position mismatch, only to create a **new** pulse for the old lyric's translation and timestamp at row 13. A repeated chorus was especially risky before both occurrences had entered the source cache. `verifiedInsertionIndex` also accepted a partial Japanese prefix with a trailing Latin source cell. The next correction indexes remembered native-getter timing by its actual `AppleLyricsBindingDiagnosticContext.position`, requires the current position and **all** observed `song_lyrics_word` cells to match, and fails closed when that proof is missing. Repeated identical Japanese text at different positions now preserves distinct timing windows.
2. **Delayed retries inherited stale source snapshots.** The first `bind` scheduled its future calls with the initial `observedSourceCells` list rather than reading native word views again. This prevented late source replacement from invalidating a retained painter. Separately, after a newly resolved context arrived but its translation TextViews were not ready, retry-only paths used non-forced `stopOldPulse()`, leaving a previous row alive. The next correction passes a fresh native-source observer into retries and forcibly stops a pending pulse whenever the resolved native line/position/source differs, while retaining exact redundant same-row bindings to avoid flicker. An observable source mismatch defers installing the new painter until its real source views are present.

New JVM regressions target position-12→13 reuse with old cells, identical repeated-chorus source at distinct native times, missing/negative adapter positions, Latin-source continuation, revised cache/song identity and source change while the new translation cells are pending. The retry observer itself also needs NPatch runtime confirmation; no device evidence is yet available for this new build. Do not mark #234's visibly doubled meaning as wholly repaired on that basis alone.

### Final compatibility review of the position-scoped candidate

An additional review found three real compatibility or identity gaps before a device release:

1. An adapter index is local to the **native adapter instance**, not unique across Apple's multiple registered lyric adapters. An index keyed by row number alone could still borrow a timed context from another presentation. The next candidate uses the actual `adapterInstance` captured by the native `onBindViewHolder` hook and a weak **identity** map of adapter → position → timed context, not just `System.identityHashCode` (which can collide).
2. The native line getter intentionally saves original text under `withRawReads`, but visible native word getters apply `AppleLyricTextTransform`. For example, the bundled Traditional→Simplified dictionary maps raw `愛` to displayed `爱`. A raw-vs-displayed exact match could reject a correct native getter. A getter captured within the current holder bind remains the authoritative source-clock owner; only **source-derived fallback** requires an exact displayed-source match, using the enabled conversion. A pending painter's source check likewise compares against displayed form. Per-word phrase-based conversion may still differ from whole-line conversion, so missing-proof fallback fails closed rather than assigning the wrong time window; that exceptional compatibility case requires a device replay.
3. The observed list can be `listOf("", "")` when the native flexbox has opaque children without `song_lyrics_word` views. This represents **unavailable** Japanese source proof, not contradicting text. The current holder getter may still paint; cached fallback must require at least one actual word character. The next candidate distinguishes those states and retains same-owner/same-position pending text only when no contradictory source is visible.

These additional fixes are regression-tested in the JVM for multiple adapter owners, traditional/simplified display, punctuation/Latin source boundaries and opaque placeholder children. They remain **source-level remedies**, not measured conclusions about the #234 frame or runtime FPS.

### Review-driven provenance and native-word transformation correction

The next read-only code review exposed another distinct stale-owner transition:

1. If a cached fallback context A matched the first visible source but its Korean meaning TextView was missing, `retryAfterLayout(A)` **wrote A to `byHolder`**, the same store used by the real native getter. After native source cells switched to B, a later retry read A as a trusted holder context. The old code could skip source revalidation and install A's painter if an old/partial Korean cell matched. The corrected resolver never mutates holder context during fallback or layout retry. **Only the actual native getter may write to `byHolder`**. Each delayed attempt re-reads the source and independently resolves a fallback. A stateful JVM test exercises A→B between retries and verifies that `forHolder()` remains empty until an actual getter write.
2. A newly arrived real getter B still must not be painted onto old visible source A: Korean translation fragments can overlap lexically (`나` can be a substring of `나는`). With **readable contradictory native source cells**, defer the pulse and retry, even when the context came from a real getter. When the native observer has only empty/opaque children, there is no negative evidence and a getter can proceed.
3. Text conversion is performed in Apple's actual **word getters**, not in a whole-line getter. The bundled phrase dictionary converts whole `反覆` to `反复`, but when Apple's source words are `反` and `覆` the visible text is still `反覆`; mixing them with `愛`→`爱` yields `反覆爱`. The corrected translation context captures raw native main-word segments at getter time and derives their displayed concatenation by applying the configured transform **to each verified word**. It only accepts the derived representation if the raw segments concatenate back to the original line. Source matching accepts exact raw or exact verified displayed representations; if word boundaries cannot be obtained and text genuinely conflicts, it fails closed. Tests cover the split-phrase and nonmatching-vector cases.

This correction is narrow to translation foreground ownership and compatibility. Its successful JVM checks, if achieved, will still not establish end-to-end device display, clipping or frame-time performance. The independent reviews have not claimed a runtime trace of #234 or an on-device reproduction of these paths.

### Delayed native meaning cells must cover the entire translated line

The source check alone cannot prove that the **translation TextViews** have finished rebinding. `shouldRenderTranslationCell` intentionally accepts fragments because Apple's long translated meaning can be split across physical native cells. But that also allowed an old complete `나` cell to match the beginning of a new expected `나는` meaning after the new source and clock were already correct. One partial match could install a pulse on the wrong TextView, and the later actual native `setText` would invalidate that pulse without necessarily scheduling an immediate new bind. This is an independently verified code path, not proof of any specific #234 frame.

The subsequent candidate checks **ordered, complete normalized meaning coverage** before installing a pulse. A complete one-character translation (`나`) remains valid; legitimate split cells (`나` + `는`) form `나는`; one stale `나` fragment, missing suffix or duplicated `나는` cells fail closed and retry. The check occurs only on native bind/retry, not on every 100-ms focus tick. An extra main-word reflection pass for conversion is also skipped when there is no holder or adapter-position consumer. The correctness and performance impact of unusual host translation-cell order and layout still require NPatch device verification.

The next focused review caught a related view-selection mismatch: the first completeness check counted `GONE`/`INVISIBLE` translation descendants even though the foreground drawable skips hidden views, and it filtered out visible **named native meaning cells** whose old text did not match the expected translation. Both behaviors could admit partial painting or reject a correct row. The final source candidate selects visible-in-holder native `lyrics_translation_line` cells **without prefiltering their text** and verifies the entire selected text; an exact-text anonymous fallback is considered only if there is no visible named native meaning cell. Visibility walks the child-to-holder ancestor chain, deliberately ignoring native alpha (the pulse sets alpha to zero itself) and temporary window detachment (prefetched holders). Regression tests cover a hidden duplicate, hidden suffix, visible stale named cell, fallback selection, complete one-glyph and multi-cell meanings. Review found no remaining Critical/Important issue in this selection path; real-device painting and post-bind visibility transitions remain unverified.

## 2026-11-21 continuation — fallback reading wake at internal word gaps

The line-wide fallback had a separate, deterministic scheduler defect that
survived the build #250 corrections. `ReadingView.setTimedText` cached only
the **first** begin timestamp of each pronunciation variant. Its on-screen
`highlightPulse` looked up future starts exclusively in that array. Consider
one verified line with real native units `[100, 200)` and `[700, 900)` ms.
At playback position `550` ms, neither unit is active and the only cached
variant start (`100` ms) is already past. The previous scheduler chose a
`400` ms delay, so under uninterrupted playback the next reading refresh
could occur at `950` ms, **250 ms after the second native word began**. Apple
Music's own Japanese word renderer has a separate clock and need not wait
for this Hangul fallback callback. This proves a *possible reading paint lag*
for sparse verified units, not a real recorded audio-clock offset or proof
that #234's particular frame used the fallback path.

The candidate correction finds the next start among **all real timed reading
units**, including repeated-chorus variants. A visible gap within `450` ms of
the next known word now uses the existing `60` ms pulse, without inventing
intermediate syllable timestamps. It also obtains one Exo clock sample per
visible reading pulse and shares that sample between paint and scheduling;
previously the pulse queried the clock separately for both. Off-screen
fallback rows no longer query the playback clock on their `400` ms idle
callback. None of these changes removes karaoke highlighting or masks a
font crop. JVM regressions cover the internal gap, repeats, disabled screen
polling, unavailable clock and the same-sample scheduling policy.

For the still-unproven **D/E** cases, a new, six-sample-only
`linewide_measured_geometry` NPatch trace records real holder/flex/reading
sizes, reading line count, source word vertical spread, and the first/last
font-metric ink bounds relative to the holder. It never logs lyric text.
Geometry is evaluated lazily only for the initial measured overlay samples,
not on every lyric frame. A future device trace can distinguish multiple
wrapped source rows in one holder from separate holder rows and test whether
the fallback glyph would overrun the measured holder. It is **diagnostics**,
not evidence that any clipping or grouping issue is already solved.

**RED observed:** the draft CI-only PR #12 targets `main` to exercise the
Android workflow while keeping reviewable stacked PR #11 untouched.
[Run #251](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/44855159424)
failed as intended at `:app:compileDebugUnitTestKotlin` with five
`Unresolved reference 'nextUpcomingBeginMs'` errors in the newly added tests.
This is a **compile-level** RED proving the helper was absent, not a
behavioral failure from running the old scheduler. The source trace and the
550→700→950 ms schedule calculation above independently establish the old
code path. Full GREEN/build verification remains pending. Device clips and
frame-time/GC evidence remain outstanding.

## Required handoff replay

- Install the final CI APK with compatible NPatch and Apple Music; record audio or player-position telemetry alongside the screen.
- Exercise user time markers 8 s, 32–37 s, 38–40 s, 44–47 s, 49–64 s and 83–90 s, plus pause/resume, repeated taps/seeks, song changes, toggles, wrap/small-screen/large-font.
- Capture `native_reading_paint_path`, `linewide_measured_geometry`, `translation_cells_bound`, `translation_painter_active/inactive`, `translation_context_missing` with the new build hash (do not reuse the older 1119 log).
- Trace main-thread frame durations and GC in the same run. An APK-only CI result cannot establish absence of stutter, clipping, duplicate draw, or actual sync offset.

## Ground rules for status

- Code evidence alone confirms an architectural failure mode, not necessarily attribution of every video frame.
- "Fixed in source" requires a regression test that failed pre-fix and passed post-fix. "Fixed and verified" also requires an app/device replay for visible rendering or performance problems.
- The old build log is not evidence of new build playback state. Preserve the logs, video and build hashes in the final report.
