# Apple Music Korean Pronunciation Reliability — Design

## Intent and observed evidence

Make Japanese/English lyrics show accurate, complete Hangul pronunciation beneath the correct Apple Music lyric line, without changing existing karaoke timing or Korean translation. On Galaxy Buddy3 / Android 16 / Apple Music 6.5.3 / NPatch Local, the 2026-11-05 log confirms that the module loads from the currently installed HyperLyrics APK, the app service produces 22 translations and 17 pronunciation lines, the Apple process receives the response, and the pronunciation hooks run. The precise GitHub Actions build number cannot be inferred from shared `8.2.2 (222029)` package metadata.

## Goals and acceptance

- No truncated reading, misplaced Hangul, duplicated fragments, spurious syllables, or lost original karaoke timing in golden fixtures and supported on-device screens.
- Keep Korean translation, Hangul reading, and their visibility toggles independent; both must survive scrolling, seeking, song changes and refresh.
- Use the source Japanese sentence for reading once, with deterministic full-line morphology and offset-bearing reading spans. Never generate readings for unknown source words without a supported fallback.
- Attach readings to Apple native timed words **only when every boundary is verified**. Otherwise use an independent, line-wide pronunciation row occupying the full lyric container width, not karaoke-word width.
- Report each line as `ALIGNED`, `LINE_WIDE`, `MISSING_UNSUPPORTED` or `HIDDEN`; never treat 17/22 as full pronunciation success without explaining five missing lines.
- Make the module's build identity and per-stage counts diagnosable in release-signed APKs without exposing source lyrics, API keys, song names, raw response, or user data.
- Preserve previous successful translations, local cache, Gemini/OpenAI-compatible/Cohere setup, multi-key rotation, Apple Music 6.5.3, NPatch Local, and signing. No unverified main merge or forced Apple Music repatch/uninstall.

## Non-goals

- No speech-to-text, audio recognition, lyric timestamp reconstruction, forced word timing, or different paid model.
- No broad UI redesign or modification of unrelated Apple Music extensions, SystemUI/Lyricon behavior, or unrelated 43-feature work.
- No guarantee of perfect pronunciation for ambiguous sung forms without audio or manual correction; represent uncertainty rather than inventing text.

## Approaches considered

1. **Keep proportional token distribution:** Small change but still inserts reading under unrelated timed karaoke cells, leading to apparent misalignment/clipping. Rejected.
2. **Force one static pronunciation row for all lyrics:** Reliable full text but discards legitimate word-level karaoke synchronization. Rejected as sole approach.
3. **Verified word alignment, otherwise dedicated full-width pronunciation row:** Preserves exact native word timing when supported and full text when not; requires more careful Apple adapter lifecycle handling. **Recommended.**

## Architecture

1. **Input:** Freeze `songId + original lyric fingerprint`, immutable source line index, line text, begin/end timestamps, and Apple native word text/times.
2. **Reading:** One Sudachi pass per full line produces kana/mora-bearing spans with original source offsets. Convert each to Hangul via a versioned, independently tested Japanese phonology mapping. Unknown reading produces an explicit missing status. Mixed Latin tokens use the existing conservative lexicon; an unsupported word does not silently turn into a Japanese sound.
3. **Alignment:** Match original source offsets and normalized source text against Apple words. If and only if complete source and joined Hangul are identical, produce a verified per-word map; otherwise choose line-wide display. Never split Hangul by character count, number of karaoke cells, or proportional weights.
4. **Transport:** Add backward-compatible optional serialized source-offset spans/status fields to `RichLyricLine`, keeping `roma` and `pronunciationWords` for older clients and independent translation fields.
5. **Apple display:** Native official pronunciation retains existing priority. Verified local readings use the existing word render plan. Unaligned local readings use a full-width view bound to the corresponding native line/RecyclerView holder. Update and remove the view for recycled holders, toggles, refresh, and song switch; native original lyric words/timing stay untouched.
6. **Caching:** Keep translation and pronunciation content under independent versioned keys; change pronunciation policy revision without deleting valuable translations. In-flight results must not reintroduce deleted records or overwrite a newer song.
7. **Observability:** Distinguish app APK build identity, NPatch-loaded module build identity, reading engine status, span coverage, alignment decision, row bind, measured overflow, and source mismatch using redacted structured stages.

## Implementation boundaries and risks

- **Apple 6.5.3 internals:** First confirm the holder binding and row container in a read-only debug trace. Native karaoke view width cannot safely display unmatched line-wide text; fall back to a supplementary TextView anchored to the same native line holder. If no safe anchor exists, *do not ship* an approximate proportional substitute.
- **Sudachi segmentation:** A kana reading of an isolated fragment may differ from the same fragment in context. Source offsets must come from the full-line pass; reject spans that cross an Apple word boundary without a provable subspan mapping.
- **Mixed text / no-reading lines:** Preserve punctuation, whitespace and English, but never fabricate Japanese mora; show explicit missing status if unsupported.
- **Offscreen RecyclerView binding:** Views must be removed or rebound across recycled holders; no stale text in adjacent lines/songs.
- **Security / size:** Keep dictionary extraction in the Enhanced process, not Apple Music. Do not log raw lyrics or keys. Logs must be size-limited and redact identifiers.

## Verification and release gate

1. Regression fixtures: minimal user-reported `駄目`, `脳みそ`, `聞こ`, `君の名は`-style snippets, repeated words, kanji/kana variants, punctuation/emoji, small kana, っ/ん/long vowel, ambiguous/OOV and mixed English; synthetic multi-line long-text karaoke timing.
2. No per-word map unless every corresponding source offset and Hangul concatenation match. Any unmatched case must display the complete line-wide Hangul without changing native words.
3. Stress cases: 320–430 px screens, large font scaling, fast scrolling, repeated seek, hide/show, source switching, song change while AI responds, no network/expired trial key, app/service death, cache delete/restart.
4. CI: focused JUnit red→green, full unit tests, lint, assembly, repeatable signing and artifact verification.
5. Device: verify in NPatch logs the unique module build identity loaded into Apple Music and the app build identity, then reproduce the reported song and at least two contrasting songs; compare screenshots/video at normal and large text. **CI success is not an on-device fix claim.**
6. Keep the PR draft until the device gate passes; hand off a tested, signed updatable APK artifact; no main merge without approval.
