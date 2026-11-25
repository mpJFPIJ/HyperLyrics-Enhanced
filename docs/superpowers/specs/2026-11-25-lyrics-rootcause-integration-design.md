# Apple Music 6.5.3 lyrics root-cause integration — design

## Intent and acceptance criteria

Repair the existing HyperLyrics Enhanced Japanese/Hangul/Korean lyric pipeline
without replacing Apple Music's Japanese word renderer, its original Exo word
clocks, seek/tap behavior, or native scroll. A Japanese visual row must be
immediately followed by its own verified Hangul reading, then the corresponding
Korean meaning. Source and reading word highlights must agree; only the current
meaning row may be bright. No fabricated word clocks, lost source characters,
or orphan Hangul jamo are acceptable.

The user explicitly chose Apple Music **6.5.3** and NPatch Local on Samsung
Android 16. Updating to **7.0.3 is out of scope**. The user also chose one
final integrated Gradle/CI/APK verification after root-cause investigation and
code changes, not repeated intermediate Android builds.

## Actual baseline and evidence limits

- Base is Draft PR #20, commit `cfb5577c3e89f0313025010e8ab743059ed9908b`.
  Prior CI #306 passed but does not establish device behavior.
- The user's `1000034205.mp4` (173.48 s, 720x1568) visibly contains Japanese
  four-row followed by Hangul four-row at ~60 seconds, and an orphan `ㄴ` at
  ~82 seconds. It predates #306; it cannot refute that build's source changes.
- The November 25 NPatch ZIP identifies Apple Music `6.5.3 (1599)` and
  HyperLyrics commit `a84acd95c30ae52dd05229a8d8c4eb753d3ba192`, run
  `45400701927` (#289). One snapshot explicitly advertises
  `languages=[ja-Hang]`, with `nativeTextLines=0`, `nativeWordLines=0`,
  `onlineTextLines=0` and `mainWordLines=30`. All five snapshots have no
  native pronunciation text/words. Advertised track availability is stronger
  evidence than a guessed server label, but it still does not establish
  whether content was fetched, selected, populated or parsed.
- The November 24 NPatch log's `linewide_holder_bound` entries are all
  `shown=0,candidate=0,sourceCells=0`, so they are not evidence of the
  problematic visible fallback layout. The video and log must not be joined
  frame-for-frame without an event/time/source identifier.

## Code-confirmed failure mechanisms

1. `AppleLineWidePronunciationRow.bind` inserts actual `ReadingView` children
   into `FullWidthAlphaGradientFlexboxLayout`; the Apple gradient operates on
   the modified child hierarchy. An outer holder foreground re-paints reading
   glyphs, so measurement and painting have different parents/owners. Whether
   this accounts for each clipped source pixel still needs device proof.
2. `AppleInterleavedReadingPolicy.plan` rejects a child with internal
   `nativeLineCount != 1`; the soft-wrap alternative requires *exactly one*
   source child and one `TextView`. All other unproven mixed/multiwrapped rows
   fall back to a single Hangul child after the final Japanese cell. This
   is a code-demonstrated route to four-Japanese-then-four-reading layout.
3. `expandAppleLyricsPronunciationLanguages` requests `ja-Latn` but neither
   `ja-Hang` nor `ja-Kore`; `ApplePronunciationPolicy.selectLanguage` accepts
   Latin tags only, while `AppleNativePronunciationLanguagePolicy.select`
   supports Japanese Hangul tags. Request, native matching, and explicit
   selection therefore use inconsistent policies. Do not infer actual server
   availability from these code paths alone.
4. The fallback reading has its own periodic highlight pulse and overlay
   paint path. Native source words and Korean meaning use separate renderers;
   no atomic update across seek, RecyclerView bind, translation focus and
   reading refresh is guaranteed merely by sharing a sampled clock.
5. `AppleNativeSoftWrapReadingOverlay` increases native source line spacing
   and paints other Hangul rows in a holder overlay. Its geometry and lifecycle
   protections are substantial, but the modification changes the very native
   layout being measured; mixed source children are still unsupported.

## Approach and tradeoffs

### A. More per-word or per-song exceptions — rejected

Fast to patch but cannot solve unknown wraps, native mask ownership or stale
holder reuse. It reproduces the prior unsuccessful cycle.

### B. Force all Hangul into Apple native word cells — rejected

Keeps Apple's renderer but assigns incorrect word clocks for unverified
Kanji/kana splits. Previously caused sounds from `溺れ` to use the next word.

### C. Source-preserving, evidence-gated ownership — selected

Keep Apple's original source and verified native pronunciation words
untouched. Unify language selection and immutable source-to-reading proof.
For unverified fallback, separate the reading paint owner from the host's
gradient and base visual-row placement on measured native geometry. Where a
physical visual row cannot be proven, do **not** fabricate syllable or
timing boundaries; preserve full text with a coarse line interval and record
the unsupported geometry. Before choosing the precise outer placement
implementation, verify the 6.5.3 parent layout, clip boundaries, child
measure contracts, and RecyclerView lifecycle, not just screenshot appearance.

## Global invariants

- Preserve original Japanese strings, native word boundaries and timers.
- No proportional source-character-to-Hangul or visual-width timing split.
- Every rendered Hangul piece has the correct song, revision, source holder,
  source text, source interval, and proven visual row identity.
- Holder detach/rebind, source text/width/font changes and song transitions
  invalidate old paintings and delayed callbacks.
- Translation has exactly one visible painter and a single live line-owner;
  stale adapter positions, partially rebound meanings, or unknown clocks do
  not brighten another line.
- Native user scroll remains authoritative; automatic follow never hijacks
  an intentional drag.
- Protect caches, API keys, NPatch Local and the existing user installation.
- Do not claim a visual, performance or audio-sync fix from JVM/CI alone.

## Evidence and release gates

1. Complete static call-chain and state-owner audit, including requesting,
   selecting, parsing and refreshing official `ja-Hang`/`ja-Kore` tracks.
2. Add failing behavioral regressions for each code-confirmed defect, then
   implement one causally isolated correction at a time in the same branch.
3. Complete combined pure policy, holder lifecycle, seek, timestamp, layout
   and text safety checks before the final Android verification run.
4. Run Gradle/CI/APK build at the final integrated gate only, per user request.
5. Replay video times ~60/~82/~130 seconds plus 8, 32–37, 38–40, 44–47,
   49–64 and 83–90 seconds on Galaxy Buddy3 / 6.5.3 / NPatch Local. Compare
   fresh build identity and device trace to the new artifact; record solved,
   partially solved, unsolved and newly introduced behavior separately.
