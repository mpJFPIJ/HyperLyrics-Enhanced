# Apple Music soft-wrap reading overlay reattachment

## Intent

Preserve the already verified Japanese-native source renderer while ensuring that
Hangul readings painted between an internally wrapped native `TextView`'s lines
do not disappear when its holder is temporarily detached and reattached.

## Constraints

- Do not modify Japanese source words, native timing, seeking, or translation.
- Never reattach a previous reading to recycled or differently measured lyrics.
- Never retain added source line spacing after unbinding or failed installation.
- Use actual native `TextView.Layout` UTF-16 line bounds, not guessed syllables.
- Keep PRs and `main` unmerged until Android 16 / NPatch / Apple Music testing.

## Approaches

1. Keep the drawable and extra spacing installed across detach. This risks stale
   painter ownership and geometry when Android recycles the holder.
2. Dispose everything on detach and rely on another adapter bind. This is the
   current behavior and leaves a visible hole if no bind occurs after reattach.
3. **Chosen:** remove painting and restore native line spacing on detach, but
   retain the overlay's immutable source snapshot. Once attached and measured,
   reinstall only when native source text, width, line offsets, font and
   original spacing still match. Full unbind/recycle still destroys the state.

## Expected behavior

- Detach removes drawing and restores source spacing without losing the ability
  to resume the same verified row.
- Reattach recreates the earlier reading slots only after compatible source
  geometry is observed, including the same internal line starts and ends.
- Pending layout defers restore. Different song/source, wrapping, source width,
  text size, or another line-spacing owner prevents stale restoration.
- A subsequent full bind can replace the old overlay safely, without doubling
  reserved spacing or leaving an invisible Japanese gap.
- Karaoke refresh, scroll follow, and translation continue through existing
  playback and layout paths; they require on-device verification.

## Validation

Test the compatibility decision on equal and mutated geometry, deferred layout,
font changes, and line-spacing ownership. Verify RED then GREEN using the
existing CI if local Gradle 9.5 cannot be downloaded. Run the entire Android
unit/lint/APK suite, then device smoke tests before any merge.
