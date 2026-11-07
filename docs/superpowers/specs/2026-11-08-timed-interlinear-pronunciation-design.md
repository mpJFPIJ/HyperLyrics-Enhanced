# Apple Music timed interlinear Hangul pronunciation

## Goal and observed failures

The user needs **original Japanese → Hangul reading → Korean translation**, in that order, on each native karaoke lyric line. The Hangul reading must animate in sync with Apple's real word timecodes, not flash its entire line when a Japanese cell becomes active. It must never expose untranslated Japanese characters as an unfilled Hangul reading cell.

The existing implementation has three independent failure modes:

1. The insertion policy walks past empty `song_lyrics_word` child values, which may represent a native translation view. It thereby positions the reading **after** the translation.
2. The full-line `ReadingView` selects the single brightest native `TextView` and applies that color to the whole Hangul line. Its 33 ms callback never checks playback time.
3. Verified/native per-word rendering and source-key/holder resolution can fall back to mixed Japanese/Hangul cells or refuse an entire available reading. Separate provider/AI failures also need explicit diagnostics.

## Selected implementation

- Keep all native Japanese lyric models, native karaoke word timestamps, native translation views, and scroll rendering intact.
- For Korean supplemental pronunciation, use one own full-width reading row immediately **after the last Japanese source view**, before an existing translation. Treat unknown empty native child text as a **barrier**, not as punctuation.
- Capture Apple's read-only main-word `begin` and `duration` for the line. Convert original character ranges and verified local pronunciation spans into Hangul syllable windows. Use the real Apple word windows as temporal anchors, interpolating syllables inside a native word only when Apple supplies no finer boundary.
- If a complete phonetic span map is not available (e.g. a manually corrected reading), use a clearly approximate per-syllable sweep between *real* Apple word timestamps; do not pretend its inferred syllable windows are official.
- Render with one native-Android `Spannable` `CharacterStyle` per Hangul syllable, individually updating dim→bright with the actual playback position every 33 ms. Seeking backward must dim later syllables again. No hook may synthesize Apple `LyricsWord` objects or rewrite timestamps.
- Tie the row and its timed data to song/revision/source and clear on recycled ViewHolder, old song or hidden state. No extra UI controls or NPatch dependencies.
- Identical chorus text is not a unique timestamp identity: keep the read-only timing windows of repeated occurrences and select the one nearest playback time for source-only fallback. Holder-specific binding retains its own native occurrence.
- When any original source word lacks native timing, do not distribute the entire pronunciation over the remaining words. Animate only independently verifiable source spans and render all unsupported syllables dim.
- Suppress the native duplicate pronunciation text when the independent Hangul row is registered, including the defensive path where an empty JNI vector could not be constructed.
- Keep previous successful branch, PR and main untouched until CI and device verification. The user will receive a GitHub Actions link immediately after upload; APK success is a separate outcome.

## Acceptance tests

- Empty translation placeholder doesn't move a Hangul row below translation.
- Punctuation-only Japanese cells remain above the Hangul row.
- Source spans and native words spanning morpheme boundaries resolve to monotonic Hangul highlight segments without Japanese leakage.
- Native `begin`/`duration` control per-syllable highlight, and seeking back reverses progress; a missing or invalid timing never causes a whole-line flash.
- Missing word timing still shows available Hangul reading, without claiming exact karaoke sync.
- Existing source/holder conflict and hidden/stale-song guards remain green.
- GitHub unit tests, Android build and sign verification succeed before reporting code as tested. Actual Apple Music UI must be validated on user's device before main merge.
