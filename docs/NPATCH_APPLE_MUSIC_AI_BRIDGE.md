# Apple Music AI translation via NPatch (experimental)

This fork adds a **non-SystemUI translation path** for Apple Music-only Xposed hosts,
including NPatch. The original SystemUI/Lyricon functionality remains in place.

## What changed

- When Apple Music's built-in provider has a synchronized `Song`, and the
  SystemUI direct bridge is not connected, it binds explicitly to an exported
  service in the **separately installed** HyperLyrics Enhanced app.
- The installed app reads its own existing OpenAI Translation configuration,
  translates in an IO coroutine using the original `AITranslator` and cache,
  and returns compressed song/translation data over AIDL.
- The existing Apple Music native-translation callback receives the result.
- The AI API key is **never sent to Apple Music** or returned through AIDL.
- The service checks the Android calling UID is associated with
  `com.apple.android.music`. Requests are size-limited, and superseded
  responses are discarded by the client.

## Requirements

1. Install the **same newly built fork** of HyperLyrics Enhanced as a normal app.
2. Patch Apple Music 6.5.3 with that same fork's Xposed module using NPatch.
   Patching Apple Music alone is insufficient: the translation service lives
   in the separately installed Enhanced app.
3. Configure `Lyrics Settings → OpenAI Translation` (key, model, URL, Korean
   target language), and enable AI translation.
4. Enable `Apple Music Optimizations → Fill Translation & Pronunciation Gaps`.
   This option is consulted by the existing Apple Music UI translation receiver.
5. Test a track containing Japanese synchronized lyrics. The new client uses
   the original Apple Music provider's fully populated song data; tracks
   without line lyrics cannot be translated by this path.

**Do not put credentials into NPatch settings, patches, or APK manifests.**

## Limitations / verification

- This is a source-level **experimental** integration, not yet tested on a
  Galaxy Buddy3. Validate on-device after a successful Gradle build.
- An APK signed with a different key cannot update the original installed APK
  in place. Back up the Enhanced app's settings before replacing it.
- The Apple Music adapter reflection exception
  `com.airbnb.epoxy.s#notifyItemChanged/1` is a separate metadata refresh
  issue; it is not evidence the AI translation HTTP call failed.
- No translation is returned if the remote service is absent, AI is disabled,
  line lyrics are absent, the configured API request fails, or the Apple Music
  display-translation feature is disabled.
- When a SystemUI bridge is actually connected the app-owned fallback is
  bypassed, preserving existing LSPosed behavior.
- The fork has not been tested against other Apple Music releases. Use the
  project's existing supported-version matrix.

## Relevant verification logs

Collect both processes when diagnosing a translation that still does not show:

- `com.apple.android.music`: `Apple Music standalone AI service unavailable`,
  `standalone AI request failed`, or native translation payload diagnostics.
- `com.juren233.hyperlyricsenhanced`: `AppleMusicAiService` and
  `AITranslator` / `OpenAiTranslationClient` messages (secrets redacted).

Distinguish three cases: no service binding, no AI result, and a successful
result that does not reach the Apple Music lyric display.

## Security note

The service is exported because the patched Apple Music APK has a different
UID from the installed Enhanced app, and therefore cannot access a private
service. The IPC implementation verifies the binder caller's package using
Android's UID/package mapping before any request is accepted. It never serves
credentials. This is not a general-purpose public translation API.

## Diagnostics added after real NPatch logs (2026-10-24)

The supplied Samsung / Apple Music 6.5.3 diagnostics show the patched module
initializes and Apple Music publishes **40 synchronized lyric lines** for
`Lemon`. In the Enhanced app, restored preferences show AI translation enabled,
the Korean target, a nonempty stored credential, and native Apple Music translation
enabled. Neither upload proves an AI request succeeded or that a translated result
was delivered: the original service wrote almost no events to the app's exported
logs.

The new diagnostic build records safe, credential-free stages:

- In NPatch's Apple Music diagnostics:
  `standalone_ai stage=module_initialized`, `song_submitted`,
  `bind_requested`, `service_connected`, `request_dispatched`,
  `reply_received`, or `ipc_failed`.
- In Enhanced's **Export All Logs**:
  `AppleMusicAiService stage=service_created`, `service_bound`,
  `request_received`, `song_decoded`, `translation_started`,
  `http_success` / `http_rejected`, `translation_completed`, and
  `result_sent`. The stage `no_translation_result` indicates that no
  translated lines were generated; `request_skipped` explains eligibility
  decisions. No raw song text, API keys, response bodies, or tokens are logged.

The original `com.airbnb.epoxy.s#notifyItemChanged/1` error remains a
**separate metadata update bug**; its presence alone does not explain the
AI bridge failure.

The source is updated but the new build must be compiled and tested before
calling this an end-to-end fix.

