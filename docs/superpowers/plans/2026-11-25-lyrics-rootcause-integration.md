# Lyrics Root-Cause Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make native Japanese, measured Hangul pronunciation and Korean meaning display and highlight consistently on Apple Music 6.5.3 without introducing fabricated timing or regressing NPatch Local.

**Architecture:** Preserve the Apple Japanese renderer and its word clocks. Repair language-track selection, share a strict source/reading proof, establish one measured owner for fallback placement/painting, then unify live playback/holder ownership across pronunciation, meaning and scroll.

**Tech Stack:** Kotlin, Android views, Xposed/NPatch, Apple Music 6.5.3, JUnit and Gradle.

**Spec:** `docs/superpowers/specs/2026-11-25-lyrics-rootcause-integration-design.md`

## Maintainer checkpoint — 2026-11-28 (source only)

### Final CI #308 compile-gate RED and source correction (awaiting rerun)

The first actual integrated GitHub Actions run after the source-only period,
[#308](https://github.com/mpJFPIJ/HyperLyrics-Enhanced/actions/runs/46102271835),
failed `compileDebugKotlin` and `compileReleaseKotlin` before JVM tests or
lint could run. Both reported precisely two unresolved references in
`AppleLyricsSupplementPronunciationSupport.kt`: a translation getter used
`revision` outside its scope despite capturing `observedRevision` for this
holder callback, and a reading bind invoked the injected `playbackHooks`
**function** as if it were already an `ApplePlaybackHooks` object. The
minimal source correction uses `observedRevision` and `playbackHooks()`.
The compiler failure itself is the reproduced RED; passing compilation,
regression tests, lint and APK integrity remain **unproven until the next
integrated CI run**. No APK was built in #308, and device acceptance is still
open.

### Pronunciation-selection diagnostics across revisions and long sessions (unexecuted)

The native pronunciation-selection hook previously emitted
`pronunciation_selection_applied` only once per song because the default
runtime-diagnostic key was just its stage. If a later same-song model revision
selected a different language or the native setter changed from an unknown to
an accepted result, the diagnostic would still show only the *first* attempt.
The selector now includes the translation-store revision, selected language
and effective setter return in its bounded diagnostic key; the song's native
pronunciation snapshot also includes that revision in its identity and log.
Separately, the three debug-only diagnostic dedup sets never removed earlier
song/row/attempt identities. They now each keep at most 1,024 recently seen
keys, refreshing duplicates without re-reporting them and evicting only the
least recently used key. Tests were authored for capacity/revisit behavior and
same-song setter/revision transitions, but **none was executed**. These changes
improve the final matched-build trace and bound debug metadata retention;
they do not establish that Apple offers a Hangul language track or that a
reading/translation row renders correctly on the Galaxy Buddy3.

### Native geometry, seek and official-track audit (no newly proven UI fix)

Rechecked the conservative measured Flexbox row gates, active Korean meaning
and Hangul clock/follow ownership, and Apple's native pronunciation-language
selector against the accumulated corrections. The still-unknown host geometry
for internally wrapped native children cannot be safely guessed from a
previous-build screenshot. The final Apple Music 6.5.3 + NPatch Local replay
must inspect `fallbackGate`, `linewide_multiwrap_geometry`,
`linewide_alpha_path`, `meaning_paint_clip`, selection revision/setter result,
seek/focus transitions and actual frame time in one matching-build capture.
No Gradle, JVM tests, lint, CI, APK or device replay has run in this batch.

### Crash-safe disk song cache publication (source candidate; unexecuted)

The next read-only audit found that both `DiskSongManager.save` and
`saveMissingLyrics` wrote compressed JSON via `File.writeBytes`, which
truncates an existing valid cache before the replacement is complete. A
write interruption could therefore corrupt the last complete native or
missing-lyrics cache entry. The candidate stages bytes in a unique file in
the destination directory, closes the complete payload, and publishes it with
an atomic filesystem move; unsupported atomic moves fail closed without
truncating the prior entry. In-process missing-lyrics writes and deletes now
share the existing disk write lock. Focused pure regression source checks
interrupted staging, visibility of the old bytes before publication, and
removal of temporary files. This does not serialize cross-process writers or
prove durability against sudden power loss (forced flash fsync is avoided on
the hot player path). No Gradle, JUnit,
lint, CI, APK or NPatch device test has run, and all acceptance tasks remain
open until the requested final combined verification gate.

### Follow-on ownership, timing, and lifecycle audit (same build; unexecuted)

The accumulated source-only batches now also cover:

- **Native source and measured pronunciation:** complete Japanese voicing-mark
  coverage, temporary-only Kana normalization, conservative ambiguous-particle
  fallback, region-qualified official Hangul tracks, literal-Kana residual
  proof across source separators, and a single unambiguous native Flex row band.
  Raw original lyrics, Apple word clocks and UTF-16 offsets are preserved.
- **Meaning and paint ownership:** conflicting exact/near translation matches,
  blank meanings inside overlapping native rows, seek-safe native clock focus,
  stale holder/adapter rejection, detach/reattach ownership, correct single
  TextView-scroll mapping, native-word alpha in the Hangul shader, and terminal
  soft-wrap source/layout proof before drawing.
- **Follow and playback:** synchronous Exo getter generation validation, a
  verified follow check when native overlap focus moves without active-set
  changes, a separate bounded overlap observer independent of Blur OFF,
  a visual blur refresh only when verified focus changes, user-drag and adapter
  protection, and reduced polling when the reading clock is frozen.
- **Source-clock index:** fail-closed same-start ambiguity, meaning selection
  precedence for the uniquely verified original Japanese row, retained recent
  holder clocks past the 512-window limit, and exact song/revision ownership
  captured at the start of each native holder bind.
- **NPatch standalone AI fallback:** retain the precise unfinished request
  across Binder disconnects, reject stale attempt callbacks, release a dead
  connection on synchronous IPC exceptions and cap consecutive immediate
  auto-reconnect attempts to avoid an unbounded loop.

Every root cause and its tradeoffs has a dated entry in the cause ledger with
focused **unexecuted** regression source. The independent source review's two
remaining important gaps (Blur OFF focus monitoring and Binder binding-state
recovery) were addressed and then re-audited for lifecycle/CPU behavior. This
is still a **source candidate**, not an accepted device fix: do not mark Tasks
1–6 complete or claim passing JVM/CI, signed artifacts, NPatch compatibility,
actual clipping/fade quality, or device frame time until the single final
combined verification gate and matching Apple Music 6.5.3 playback evidence.


Literal Kana transcription now canonically composes only its **temporary
reading buffer**. That permits one intact decomposed native `が` cell to
reproduce `가` and retain its real Apple word clock/row proof, while a
physical cell boundary `か|゙` still fails closed. Raw original strings,
UTF-16 anchors and cache identity remain byte-for-byte unchanged; behavioral
tests have been authored but not run.

The native-word projection now also treats punctuation/whitespace around a
residual Kana source as **separators**, not as permission to guess its Hangul.
Previously `あ こ` with selected `아카` could assign 카 to Apple's real こ
clock; the source-only candidate requires the existing context-independent
Kana conversion proof even across those separators. Pure tests retain the
correct 아코 and one genuinely unknown Kanji word. Playback still unverified.

The Korean meaning cache now keeps ambiguous exact source/time keys as
**negative evidence** instead of selecting the last duplicate payload.
Nearby time lookup also evaluates every disagreeing candidate, rather than
letting a second agreeing record hide a third conflict. Standalone-AI and
ordinary provider lookups share this contract, with tests for source input
order and safely distant candidates. Actual receipt duplicates and rendering
remain unobserved until the final test/device gate.

A new measured-row classifier audit showed that pairwise overlapping native
Flexbox children do not always share a **common** vertical row band. The
old union-bottom classifier could group `[0,10),[8,18),[16,26)` as one
Japanese row. Its candidate now rejects such chains using the whole row's
vertical intersection while retaining genuinely overlapping offset peers.
Only pure source regressions and static inspection exist, not an Android
layout measurement or proof of visual clipping resolution.

The follow-on official-track parser review found that a `ja-Kore-JP` or
`ja-Hang-KR` BCP 47 language-script-region tag was excluded by the old
script-at-end check, while malformed `ja-x-Kore` could appear eligible.
The shared Apple matcher/song selector now identifies the adjacent Japanese
script subtag and returns Apple's original qualified tag. Pure tests cover
the positive and negative cases. This has not been run against Gradle,
Apple Music 6.5.3 or a real `setPronunciation` result; no official-track
availability or rendered lyric is claimed from this static parser fix.

The follow-up traced Japanese voicing marks across the **shared** source
identity/visual-row/native-clock contract. Before this batch, the normalized
source key discarded U+3099–U+309C while earlier proof layers correctly
treated them as sound-changing marks. As a result, `か` and decomposed `が`
could match the same holder/source cache entry or meaning at an otherwise
unique native time. The native timed-reading path also counted only Unicode
letters/digits, so preserving the marks in the key **alone** would desynchronize
its offset and word-window calculations. The candidate uses one code-point
content predicate across source keys, row coverage, native word source offsets
and timed sound-source offsets; a span ending before its mark still cannot
claim the preceding unvoiced word's clock. The native timed sound locator also
rejects a non-Hangul glyph or Japanese voicing mark smuggled into its selected
Korean reading instead of treating that sequence as a valid word clock.
Rootless/Apple source and provider
payloads are never normalized or rewritten. Pure regressions cover distinct
holder/store identities, genuine voiced original clocks, cross-cell detached
marks, and unchanged punctuation matching. **No tests, Gradle/CI/APK or
matching-build NPatch/device playback have run.** Precomposed/decomposed
canonical equivalence is deliberately not guessed; exact source identity
fails closed rather than transferring unverified UTF-16 clocks.

The independent source review then found **four Important cross-consumer
omissions**, all addressed in a separate source-only correction. A complete
provider token was being split at `か|゙愛` by the shared Kana/Han residual
splitter even though its original token endpoint was valid; a punctuation +
orphan mark could qualify as a separate spoken source token; a holder could
insert Hangul between `か` and a mark in its following native child; and the
native-word projection path could skip an omitted mark or accept a token that
ended immediately before it. Source-significant marks and independently
spoken base letters now have **different** shared predicates, hosted in the
pure Japanese transcription module and applied through the provider, native
projection, word alignment, holder, measured rows and native timed windows.
Both internal word/row splitting and fresh native-word/token boundaries must
respect mark attachment. Pure regression source covers split mixed Han tokens,
orphan mark-only cells, omitted native marks, selected Korean sound contamination
and valid intact voiced native words. Review and static inspection do not prove
test success: **no executable gate has run; final integrated tests and actual
Apple Music 6.5.3/NPatch playback remain pending**.

## Maintainer checkpoint — 2026-11-27 (source only)

The normal Flexbox-row phonetic-anchor audit found a distinct, code-proven
fallback: the same provider's **sound-matched partial** source spans can
identify every Hangul sound except one Kanji-containing source interval,
yet the measured-row planner previously required complete spans (or a wholly
literal-kana line) and discarded those anchors. The next source-only batch
recovers **one** residual Han-containing interval from exact, unique ordered
Hangul anchors, but only if all its Japanese source letters belong to **one
actual measured Flexbox row**. Kana inside the gap must independently match
the residual sound; repeated anchors, multiple gaps, unknown timing, changed
raw source, incompatible kana and cross-row gaps still fail closed. The
independent Apple word clocks are never synthesized or retimed; uncovered
Hangul remains untimed. Behavioral source tests are **UNEXECUTED**. See the
matching cause-ledger entry. This does not prove the earlier four-row video
used partial anchors: the matching-build numeric `fallbackGate` trace is still
required.
Independent source review found three correctness gaps in the first candidate:
Kana segments accidentally combined across source spaces (`こ う` versus
`こう`), supplementary non-Hangul code points hidden by Char-based sound
filtering, and an unowned combining voicing mark (`か` + U+3099–U+309C) that
changed the source reading outside a claimed anchor. The follow-up separates
Kana at punctuation/space, validates all sound letters by Unicode code point,
and requires Japanese voicing marks to belong to a real source span without
bisecting a token boundary. Regression source covers each fail-closed gate.
The existing single-TextView soft-wrap planner deliberately shares this
**measured** one-gap rule only when the unknown source interval occupies a
single actual `Layout` line; multi-line gaps remain unsupported. None of
these follow-up tests has been executed.

The latest geometry-path follow-up permits **partial** measured-row insertion
only when the sole internally wrapped native source cell is the final Flexbox
child on its own final row. Earlier verified Flex rows can now retain their
readings rather than falling back wholesale; the wrapped cell's entire reading
still follows it without splitting its internal TextView lines. Wrapped cells
with peers or earlier wraps remain unsupported. The pure regression source,
original-clock assertions and `terminalWrappedCell` diagnostic are **not
executed**; see
`docs/diagnostics/2026-11-27-terminal-native-wrap-projection.md`. This cannot
establish that the older 6.5.3 video used this branch or that Android clipped
glyphs are fixed.

The detailed checkboxes below remain open until their **entire** acceptance
criterion, including the user-deferred runnable gate where applicable, is
verified. The implementation is not at zero: the tracked cause ledger records
specific completed source fixes for Tasks 1–5, including official Hangul track
requests, Unicode- and timing-safe phonetic projection, native-word/outer-paint
ownership, duplicate Korean meaning prevention, seek/follow/restore provenance,
and lifecycle/re-measure controls. The most recent pronunciation pass adds
offscreen-clock sampling guards, generation-scoped delayed rebinds, allocation-
free installed soft-wrap geometry checks and native Typeface change detection.

The subsequent temporal/follow review added strict current-revision lookup for
holder-bound native time windows; resets follow/cooldown across song or source
changes even when Apple's adapter and row integers remain identical; and
preserves explicit user browsing across same-song content refresh. The native
active-line hook and idle blur frame now share one nullable-observation state
transition, so either callback order creates follow ownership while failed
reflection cannot erase valid state. Unknown overlapping timing preserves
Apple's original focus without arbitrarily dimming it via our custom blur, and
a single failed active observation no longer forgets the last verified focus.
Independent second-pass review found no further Critical/Important source issue
in this range. **All new executable regression tests remain unexecuted**.

The 2026-11-27 translation continuation closed four additional source-only
identity and timing gaps: an outgoing holder getter no longer masks a newer
song/revision/row/source-verified occurrence; equal-start meaning windows do
not invent a focus from adapter order without unique native corroboration;
queued meaning layout retries recheck current translation visibility and
Korean target language; and a bound holder whose paint generation was revoked
can no longer steal focus from a verified live painter. Pure regressions were
authored for all four cases, but **none were executed**. Their device frequency,
foreground transitions and Android layout/clip interactions are unknown until
the consolidated final gate. The plan's Task 5 and Task 6 boxes stay open.
An independent reviewer found an important ambiguity in the initial revoked-
holder exclusion: removed rows could no longer prove that another equal-clock
row was *not* current. The follow-up keeps revoked clock candidates as negative
evidence, and checks paint rights only after selecting a unique native owner.
The same review found installed Pulses did not recheck settings if Apple's
forced full rebind failed; the shared refresh and drawable owner now check
live Korean-meaning eligibility and retire restored painters when disabled.
Both follow-ups have additional regression source, also **UNEXECUTED**.
The reviewer additionally identified repeated SharedPreferences reads from the
drawable owner gate. The live gate is retained to reject disabled paint before
the next 100-ms refresh; per-frame call counts and latency need device tracing
before adding a cache that could briefly authorize stale content. Eligibility
read exceptions now fail closed in both the drawable and refresh paths.

The next Task 3 source-only investigation confirmed that multiple native
Flexbox children with one or more **internal** TextView soft wraps still have
no physically verified insertion point; arbitrary interleaving would alter
native sibling layout and risk orphan reading/timing. A new bounded,
lyrics-free `linewide_multiwrap_geometry` trace records whether those wrapped
children share a flex row with peers, the number of native internal lines,
and complete native layout counts. The existing complete-text fallback and
Japanese renderer remain unchanged. A pure geometry/transport regression
source was added; all tests are still **UNEXECUTED**. The final matching-build
trace must decide whether extending the soft-wrap overlay is geometrically
safe, rather than treating an old silent video as such proof.
An independent source reviewer found that emission after ReadingView creation
missed settled **reuse** binds; it has been moved before reuse. The same
review distinguished explicit native hard line breaks from automatic soft
wraps, rejected wordless/disjoint reverse-order peer evidence, and tightened
the bounded rootless trace's field-order regression. Those corrections remain
source-only and **UNEXECUTED** under the final verification restriction.
Additional review removed an unconditional per-reuse word-Layout scan from
the ordinary bind path, replaced quadratic reversed-bound detection with a
linear running-maximum check, and compares explicit breaks against the
Layout's own transformed text at its UTF-16 line-end offsets. The telemetry
is limited to the measured, otherwise unsupported multi-child case.

The following Task 4 inspection also identified an **unmeasured alpha-path
difference** between the two Hangul foreground implementations. Ordinary
ReadingView styles sample native-word alpha only until the Flexbox, whereas
their holder-level foreground draws outside that Flexbox. The internal-wrap
painter instead samples through every native ancestor up to the holder.
A new privacy-bounded, lazy `linewide_alpha_path` diagnostic compares both
products in integer per-mille units during live outer-foreground refreshes.
It records no lyric text or view identifiers and does not change rendered
opacity or Apple's native shader. Matching-build evidence of non-unit native
Flexbox/ancestor alpha is required before choosing a paint correction; pure
arithmetic and transport regressions are **UNEXECUTED**.

A separate Task 4/5 source inspection found `AppleReadingKaraokeStyle` rebuilt
a `LinearGradient` on every partially lit glyph draw, including an unchanged
paused glyph repeatedly invalidated by its host. An exact-parameter per-style
shader cache now reuses the same native gradient until progress, feather or
brightness actually changes. No source text, word clocks, host shader or
geometry is modified. A pure shader-reuse regression was authored but is
**UNEXECUTED**; active frame cost and measured jank remain open.
The same active reading's 16-ms style refresh also rebuilt two alpha lists
every pulse. Its native-word alpha path now writes into a reused indexed
primitive buffer and tracks the fallback maximum incrementally; removed
source words no longer risk stale samples after holder reuse. A regression
source checks detach/rebind and empty observations, also **UNEXECUTED**.
Finally, `ReadingView.onLayout` no longer rebuilds every timed Hangul glyph
width map when Apple's holder only moves and the identical native Layout and
font metrics persist. An allocation-free identity tracker invalidates on
changed Layout/width/line count/font shaping or a fresh timed-text bind.
Focused same-layout, new-wrap/font and same-size-rebind regressions were
authored, **UNEXECUTED**; the native source renderer is unaffected.

### 2026-11-28 long source-only maintenance checkpoint (no runnable gate)

The subsequent long-form audit followed the user's requirement to repair
individually demonstrated defects in small reviewable commits and to defer
all executable verification to the final combined gate. Code-level repairs
now include Japanese/Hangul language tags, sound/source coverage, native
Flexbox overlap vetoes, active-meaning uniqueness, live Exo seek ownership,
native holder/adapter/revision ties, overlapping-row follow wake, hidden-page
poll throttling, frozen reading pulse recovery, AI Binder replay/reconnect
bounds, shader/alpha/clip path safeguards, and bounded recent native clock,
meaning occurrence, native-availability, timing-alias and line-wide reading
caches. The per-change ledger documents the exact previously unsafe branch,
regression source and limitation without claiming a reproduced device failure.

A separate user-data preservation sweep tightened personal lyric correction
records: reject damaged/noncanonical data before any rewrite; restore legacy
AtomicFile backup visibility; surface oversize/read failures instead of empty
records; and refresh the persisted file before each infrequent edit to avoid
clobbering another editor's intervening rows. Both online song and missing-
lyrics cache filenames now stay confined to their intended flat directories
even for path-like or overlong song IDs, preserving ordinary legacy names.
The cache's old unsafe nested paths are not migrated. True simultaneous
cross-process correction writes are not yet transactional, and generic disk
song cache writes are not yet made crash-atomic; both require scoped follow-up
instead of being declared solved by the above guards.

**Verification boundary is unchanged:** all new unit/regression cases are
source artifacts and have **not run**. No Gradle, lint, CI, APK, signing,
device replay or frame/clip measurements occurred. No acceptance checkbox
below is closed based on static edits alone.

Still unresolved, without guessing a visual fix:

1. A multi-child native line with **internal** TextView wraps has no proven
   insertion point between its original Japanese glyph lines; the safe
   fallback remains one unsplit reading unless native measured offsets prove
   a physical row for every required Hangul fragment.
2. The inherited host clip, our holder-level hard clip, ancestor alpha, native
   word shader and actual glyph overhang need **matching-build** Android
   measurements before adjusting clipping or gradient ownership. Older video
   fragments cannot identify which current paint operation caused them.
3. Requested/advertised official `ja-Hang` and `ja-Kore` tags do not prove
   Apple delivered a usable track. Observe materialized native source and
   pronunciation contents together with the chosen policy on-device.
4. All new regression source and the larger accumulated branch remain
   **UNEXECUTED** under the user's final-only verification restriction.
   Task 6's JVM, lint, APK, hash/ABI, CI and same-build NPatch replay are
   therefore genuine gates, not administrative cleanup.

## Global Constraints

- Apple Music 6.5.3 only; 7.0.3 postponed.
- Existing rootless NPatch Local installation, Japanese native karaoke, translation and pronunciation cache are preserved.
- No unverified per-syllable/per-word clock interpolation.
- No intermediate CI/APK builds. The user explicitly requested one final integrated verification.
- Never merge PR #20 or claim device success based only on Kotlin CI.

## Review Focus

1. Mixed Japanese Kanji/kana official `ja-Hang`/`ja-Kore` track competes with available Latin and AI fallbacks — verify selected content and native timestamps.
2. Several native source children with one or more internally soft-wrapped TextViews — Hangul cannot pool after all source rows or attach to the wrong row.
3. Two repeated identical choruses and a backward/forward seek — stale timer, adapter row or old song never owns a new highlight.
4. Rotated/narrow/large-font layout followed by holder detach and reattach — no clipped glyph, duplicate paint, stale soft-wrap gap or orphan row.
5. Missing/partial phonetics, supplementary Han, kana digraph, orphan jamo and delayed translation fragment — no invalid split, lost original, invented clock or double meaning.

---

### Task 1: Complete evidence and cause ledger

**Files:** Update `docs/diagnostics/2026-11-20-lyrics-cause-ledger.md`, referencing the current spec and new video, without treating the older #289 ZIP as #306 runtime evidence.

- [ ] Trace raw TTML/request languages → Apple native song model → selected official/AI phonetics → holder rows, with provenance and revision at each boundary.
- [ ] Trace source native word clock, reading fallback pulse, translation focus and auto-follow independently on seek/paused/recyle paths.
- [ ] Record the exact Flexbox/holder child-count and clip contract that would make an outer reading implementation safe.
- [ ] Mark every defect as code-proven, device-observed only, or unsupported; name a focused regression for each code-proven case.

### Task 2: Consistent official Japanese Hangul language policy

**Files:** `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/ApplePronunciationPolicy.kt`, `lyrics/AppleNativePronunciationLanguagePolicy.kt`, `lyrics/AppleSupplementTextHooks.kt`, `metadata/AppleMetadataPolicies.kt`; matching policy tests.

**Interfaces:** Preserve `AppleNativePronunciationLanguagePolicy.select(languages, hangulFirst, allowNative, hasLatinText, supplementLanguage): String?`; unify official match and explicit application under the same priority/availability policy.

- [ ] Add tests showing `ja-Hang` and `ja-Kore` are requested/selected when available and selected by user priority; preserve Latin preference, Mandarin hiding, native-off and fallback behavior.
- [ ] Confirm those assertions fail on the original source for the missing request/match cases; user-requested Gradle runs remain deferred until the final test gate.
- [ ] Implement a consistent choice across request candidate expansion, runtime matching and explicit song selection. Instrument track availability versus successful `setPronunciation` separately.
- [ ] Verify source diff and test source changes; keep the change independently reviewable.

### Task 3: Correct measured Japanese↔Hangul row pairing

**Files:** `lyrics/AppleInterleavedReadingPolicy.kt`, `lyrics/AppleNativeSoftWrapReadingOverlay.kt`, `lyrics/AppleLineWidePronunciationRow.kt`, `root/aitrans/VerifiedJapaneseFragmentReading.kt`; associated tests.

**Interfaces:** `AppleInterleavedReadingPolicy.plan(sourceText, pronunciation, cells, spans): List<AppleInterleavedReadingRow>?` continues to fail closed; a new geometry plan must explicitly represent internal native TextView soft lines and reject contradictory offsets rather than guessing them.

- [ ] Add layout policy regressions for multiple source cells with internal soft wraps, mixed physical/logical lines, source-only remeasurement, UTF-16 boundaries and invalid span coverage.
- [ ] Prove each proposed visual row's actual source and reading ranges; no source-count/width proportional split.
- [ ] Implement source-preserving interleaving only when 6.5.3 geometry supports it; unknown layouts keep complete reading and diagnostic status, never invented word placement.
- [ ] Exercise view reuse, stale measurement, width change and independent pronunciation toggles in policy/lifecycle tests.

### Task 4: Eliminate competing native-gradient and fallback paint ownership

**Files:** `lyrics/AppleLineWidePronunciationRow.kt`, `lyrics/AppleHangulReadingForeground.kt`, `lyrics/AppleNativeSoftWrapReadingOverlay.kt`; paint ownership tests.

- [ ] Add ownership tests for alpha/gradient native fallback transitions, overlay attach/detach, layout invalidation, identical source and changed source cases.
- [ ] Separate fallback paint from Apple's masked original hierarchy without replacing native Japanese source words or their touch target.
- [ ] Ensure one reading owner at a time; remove old overlays and modified line spacing atomically when binding/scope changes.
- [ ] Confirm actual pixel clipping and partial-white behavior only during final on-device replay.

### Task 5: Harmonize temporal and adapter ownership

**Files:** `lyrics/AppleTimedPronunciationHighlightPolicy.kt`, `lyrics/AppleNativeTranslationHighlight.kt`, scroll-follow policies and playback hooks; corresponding tests.

- [ ] Add tests for seek backward/forward, pause/resume, old asynchronous position, stale holder generation, repeated chorus, song/revision transition and user drag.
- [ ] Make live playback selection and adapter/source ownership checks consistent without overriding Apple's accepted Exo clock.
- [ ] Ensure a missing clock/ambiguous word keeps unsupported fragments dim, and current meaning group alone can focus.
- [ ] Remove redundant periodic invalidations only when they are proven unnecessary; quantify device frame times at final replay.

### Task 6: Final integrated verification and handoff

- [ ] Review every new test against a concrete regression that would fail before the repair; check all previous requirements and source diff.
- [ ] Run targeted JVM tests, full unit suite, Debug/Release Lint, signed Debug/Release build, ABI and artifact byte/hash checks as one coordinated final verification phase.
- [ ] Keep PR draft; publish only verified changes and provide the exact commit, CI run, SHA-256, and downloadable APK after success.
- [ ] Ask for (or use available) matching real-device video and NPatch diagnostics from the new build; do not call visual behavior fixed without them.
