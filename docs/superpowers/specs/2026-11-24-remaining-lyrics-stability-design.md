# Remaining Lyrics Stability — Verified Recovery Design

## Intent

Continue fixing outstanding Apple Music 6.5.3 / Android 16 / NPatch lyric issues without replacing Apple's native Japanese karaoke renderer. The user requires Japanese original followed immediately by aligned Hangul reading and then Korean translation, with correct timed highlights, scrolling, recycling and no duplicate layers.

## Goals

- Reject a Korean translation foreground when its lyric song/revision no longer owns the live presentation, even before a new translation getter populates the occurrence index.
- Recheck an active lyric that remains outside the viewport after a transient native scroll/layout transition without stealing control during user browsing.
- Retain one paint owner and native source/click/timing behavior. Keep the soft-wrap resume work in PR #17 intact.
- Instrument any device-only uncertainties rather than claiming they are fixed from a passing JVM test.

## Non-goals / constraints

- Do not infer Japanese-to-Hangul character cuts from raw string lengths.
- Do not replace Apple's native auto-scroll or timed word renderer with a custom RecyclerView.
- Do not create competing translation foregrounds or block explicit user scrolling.
- No merge or real-device success claim without evidence from the Galaxy Buddy3.

## Approaches considered

1. **Guarded native extensions (selected):** Add explicit current presentation ownership and bounded recheck semantics to existing overlay and follow-state code. Minimal compatibility surface, testable policies.
2. **Rendering-only visibility fixes:** Hide duplicate/focused cells through alpha changes. Rejected because it hides symptoms without correcting stale ownership or missing follow events.
3. **Full custom karaoke/scroll renderer:** Could theoretically own all timing and layout, but risks native shader, click targets, performance, and Apple Music/NPatch compatibility. Rejected.

## Evidence and uncertainty

The current `AppleNativeTranslationHighlight.refreshVisibleGroups` compares a pulse to the *last indexed translation song/revision*, not the current presentation. That index can be old between song selection and the next getter; a still-attached old pulse may focus using the new player's clock.

`AppleLyricsBlurHooks.onAppleLyricsActiveLinesUpdated` performs a single delayed offscreen recovery check per changed active-position set. If it runs while the list is settling or layout has no visible rows, the same active row can remain stranded without another check.

Neither static fact proves all historical device symptoms. Translation detach/reattach and visual line alignment require target-device evidence; preserve existing behavior until a corresponding test or diagnostic confirms a safe correction.
