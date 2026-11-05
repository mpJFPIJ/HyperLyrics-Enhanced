# Apple Music 6.5.3 — Hangul pronunciation device regression

## Current gate (2026-11-05)

The draft diagnostic build is **not** the final UI fix. Its only Apple Music View change is a read-only probe. The new complete-line UI row must not be enabled until a valid host holder anchor and child layout are observed. Do not repatch or uninstall Apple Music just to test this branch.

## What to collect (do not send keys or raw lyric export)

1. Install the personally signed APK from the draft PR's successful `personal-updatable-signed` artifact, retaining existing app data and NPatch Local settings. Avoid temporary debug-key artifacts, which do not update the existing APK.
2. Open Apple Music and play `IRIS OUT`, then open its lyrics screen and scroll through a long Japanese line. Turn Hangul pronunciation and Korean translation on, then off/on separately.
3. Export the NPatch app diagnostics ZIP, plus Enhanced's redacted diagnostics if available.
4. Verify the app service reports `stage=service_created buildCommit=<SHA> buildRun=<RUN_ID>` and the loaded module reports `stage=module_loaded buildCommit=<SAME_SHA> buildRun=<SAME_RUN_ID>`. Matching package version alone is not proof.
5. Look for `ApplePronunciationAnchor` `stage=anchor_probe` entries: `holderWidth`, `holderHeight`, `holderChildren`, `holderGroup`, `position`, root and child view classes. Class names and dimensions are not lyric content.

## Final device approval matrix (once safe full-line row is implemented)

| Case | Required result |
| --- | --- |
| Repeated `駄目` and mid-sentence `脳みそ` | No unrelated `키코` insertion; no syllable duplication, offset shift or clipping |
| A native word divides a Sudachi morpheme | Full line-wide row; **never** guessed per-word timing |
| 320, 360, 430 px and large fonts | Full Hangul wraps within its lyric holder without overlap |
| Seek, pause, repeat and fast scroll | Original Apple karaoke timings unchanged; no holder leakage |
| Song A → Song B while AI request is pending | No stale lyrics, translations or Hangul from A appear on B |
| Translation on/off and reading on/off | Completely independent toggles, including after source change/restart |
| Offline or expired translation API trial | Valid offline pronunciation remains usable; cached translation persists |
| `readingLines` less than `originalLines` | Missing unsupported rows explicitly counted; no false “everything completed” |
| NPatch app/module process reload | Loaded build identity matches installed signed app |

If the holder cannot safely accommodate a dynamic full-width TextView, do not switch on an approximate proportional fallback. Retain the draft release gate and capture another structure probe.
