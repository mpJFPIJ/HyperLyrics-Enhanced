# Apple Music 6.5.3 — Korean pronunciation device regression

This checklist is for the personal-updatable **draft PR** build. CI validates Kotlin
and APK signing; only tests in the actual NPatch Local / Apple Music process can
confirm that the native flexbox accepts the full-width pronunciation child.

## Do not change the working installation while testing

- Device: Galaxy Buddy3 / Android 16 / Apple Music 6.5.3 / NPatch Local.
- Update only the existing HyperLyrics Enhanced app to the personal-signed APK.
- Do not uninstall Apple Music, reset application data, repatch, change permissions,
  or remove existing cached lyrics/API keys.
- Confirm the exact module `buildCommit` and `buildRun` loaded in the NPatch log;
  app version `8.2.2` on its own does **not** identify a CI build.

## Source/translation/pronunciation invariants

1. Play **IRIS OUT — Kenshi Yonezu**. Observe lines around `00:55`, `01:05`
   and later in the song. Source line, Korean meaning and Hangul sound must
   independently appear and remain correct during scroll or seek.
2. A Japanese phrase such as `脳みその中から` must **not** show a phonetic sequence
   split artificially into `나 / 카카라` by unrelated karaoke word cells. For
   unverified word mapping, the complete reading must wrap as one TextView across
   the full source line width. It may wrap at actual phrase boundaries. AI-inserted
   spaces **inside** a locally verified Japanese word must be discarded.
3. No clipped, half-visible or foreign-line Hangul syllables at large system
   font size, long lines, narrow phone width and fast seeking.
4. Where exact source-span/Apple-word alignment exists, original native word
   timestamps and the word-level karaoke animation must be unchanged.
5. Translation remains the same as the prior good version; translation visibility
   and pronunciation visibility can be toggled independently and repeatedly.
6. Stress holder reuse: rapidly scroll across 30+ lines, seek back and forth,
   change song, return, pause/resume and reopen the lyrics page. No previous
   line's reading may appear beneath a new source line.
7. Test Japanese kanji + kana, Japanese-only kana, mixed English/Japanese,
   repeated words, songs with official native romanization, songs with no
   phonetic result, and background-vocal/duet lines.
8. Offline/expired-key path: cached translation remains visible; unknown
   readings are represented as `MISSING_UNSUPPORTED` and do not become fabricated
   sounds or a misleading "all pronunciation completed" success.
9. When a 22-line song has only 17 supported readings, the progress label must
   report `17/22줄` rather than an unqualified complete pronunciation result.

## Privacy-safe diagnostics

Inspect the exported NPatch ZIP for:

- `stage=module_loaded buildCommit=… buildRun=…`: matches the installed artifact.
- `stage=anchor_probe`: holder `karaoke_lyrics_line_container` and child
  `karaoke_line_flexbox` with nonzero measured width.
- `stage=linewide_registered`: registration counts and whether the line getter
  ran under the holder bind stack (`holderKnown=1/0`).
- `stage=linewide_holder_bound`: `shown=1` for valid full-line fallback;
  `sourceMatch=0` must **never** produce a visible row.
- App service `stage=reading_finished`: `originalLines`, `translationLines`,
  `readingLines`, `alignedLines`, `lineWideLines`, `missingLines`.

Never export API keys, authorization headers, full source lyrics, translated
texts, or personally identifying song metadata in diagnostic messages.

## Release gate

Keep the PR **draft** and leave `main` unchanged until all Kotlin tests,
lint, release ABI/signature checks and the phone regression above pass. A
successful CI run by itself does **not** prove that Apple Music renders or
recycles the injected view correctly.
