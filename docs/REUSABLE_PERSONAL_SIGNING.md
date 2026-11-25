# Repeatable personal APK updates (NPatch / HyperLyrics Enhanced)

This fork supports a **single reusable Android signing identity** for APKs
built by pull requests originating inside the owner's repository.

## One-time setup

1. **Keep the signing bundle private.** The owner creates a PKCS#12 key
   (alias `hle`) and combines it with its password in this format:
   `password|base64-encoded-pkcs12`. Do **not** commit either part to Git.
2. In the fork, open **Settings → Secrets and variables → Actions → New repository secret**.
3. Secret name: `HLE_SIGNING_BUNDLE`; secret value: the **complete single
   line** containing `password|base64-pkcs12`. Save it.
4. Trigger the existing draft PR workflow by pushing a commit to the PR branch
   (or reopen the PR, if appropriate). Builds on the same-repo PR automatically
   load the private key, sign the APK using Gradle, and verify its signature.
5. Download the artifact beginning `personal-updatable-signed-`, which is
   the debug APK signed with the same key on subsequent runs.

**Important:** The currently installed APK was produced using a disposable
GitHub Actions debug key. You must back up Enhanced settings and uninstall
**the Enhanced app only, once**, then install the first personal-signed build
and restore the settings. Future personal-signed builds from this fork can
update the app without uninstalling it. Android rejects an install over the
same package when the signing identities differ.

## Notes

- Treat the bundle as a **private signing identity**: anyone holding it can
  sign APKs that would update this app. Store a backup securely outside of
  GitHub. Do not post it into issues, PRs, chats, or source code.
- The GitHub Actions repository secret is never committed to Git, printed
  into build logs, or included in the downloadable APK/artifact.
- If the secret is absent, the CI still creates a debugging APK, but its
  artifact name starts `temporary-debug-signature-not-updatable-`.
  **That artifact is intentionally unsuitable for future seamless updates.**
- The CI verifies the APK using `apksigner verify --print-certs` before
  exposing it under the personal-updatable artifact name.
- Same-repository PR workflows can access the secret; PRs originating in
  external forks cannot. Do not blindly run untrusted code with personal
  signing secrets.
- Keeping the **same applicationId** and the **same signing key** is required
  for Android app upgrades. Keep versionCode nondecreasing. The upstream
  release process and its existing `SIGNING_KEY`/password secret names are
  left unchanged.
- A local-clone NPatch module may read the newly installed module without
  a separate repatch, but verify the new module revision in NPatch diagnostics
  on your device. Do not delete the Apple Music clone or its data just to
  update Enhanced.

## Troubleshooting

- *App not installed* on the **first** personal-signed build:
  package signature mismatch; back up and uninstall the Enhanced app once.
- *App not installed* on later personal-signed builds: verify the
  `HLE_SIGNING_BUNDLE` secret and APK artifact prefix, then compare signing
  fingerprints; do **not** repeatedly uninstall.
- CI artifact starts with `temporary-debug-`: the secret is missing or
  unavailable for that PR; not safe to use as a repeatable update.
- CI fails the signature verification step: do not install the APK.

## First secret-enabled verification

After registering `HLE_SIGNING_BUNDLE`, a new push to this branch triggers
PR #1's build. Verify the run produced the artifact prefix
`personal-updatable-signed-`, rather than the temporary-debug prefix.
Only the former is appropriate for seamless future updates.
