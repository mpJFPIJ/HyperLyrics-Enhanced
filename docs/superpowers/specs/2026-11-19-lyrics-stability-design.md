# Apple Music lyric rendering stability

## Intent

Fix the observed lyric holder churn, redundant full adapter refreshes, late Hangul highlighting, premature multi-row visual focus, and unsafe blur cleanup without changing Apple Music 6.5.3 support, Korean three-line presentation, NPatch support, or the already-repaired duplicate translation behavior.

## Evidence and limits

- 2026-11-19 NPatch traces record two holder binds within 6 ms, two `notifyDataSetChanged` refreshes within 2 ms, native active positions `[5, 6]`, and a transient offscreen active row.
- These device logs are from #217. The working tree contains uncommitted #225 changes; preserve them. No current-device evidence establishes that one specific GPU effect produces the reported white stripe.
- Do not force-scroll during a user-initiated lyric browse or silently disable user-requested blur or system fonts.

## Approaches

1. **Selected: scoped lifecycle stabilization.** Coalesce nested holder binds and same-frame refreshes, reuse an unchanged Hangul view, protect host render effects, select one visual focus during a native transition, and pre-wake imminent Hangul timing. Keep the existing host renderer.
2. **Rejected: rewrite Apple native lyric rendering.** Would require reconstructing native word geometry and risk clipping, timing, and compatibility regressions.
3. **Rejected: disable blur, fonts, or translation.** Might mask symptoms while removing supported features and obscuring root causes.

## Constraints

- Source lyrics and Apple native word animation must not be altered.
- Translation remains under its corresponding original/reading, and only current Korean meaning receives the active tone.
- Preserve existing dirty worktree edits; do not merge into `main` without explicit authorization.
- Changes lacking a reproducible device-level cause should be guarded, reversible and diagnosed, not represented as confirmed fixes.
