#!/usr/bin/env bash
# Prepare a private, reusable signing identity only for PRs from this repository.
# HLE_SIGNING_BUNDLE is a single GitHub Actions encrypted secret:
#   <strong-password>|<base64(PKCS12 keystore containing alias "hle")>
# Never print the bundle, keystore, or passwords.
set -euo pipefail

if [[ -z "${GITHUB_ENV:-}" || -z "${GITHUB_OUTPUT:-}" || -z "${RUNNER_TEMP:-}" ]]; then
    echo "GitHub Actions environment is required" >&2
    exit 1
fi

if [[ -z "${HLE_SIGNING_BUNDLE:-}" ]]; then
    echo "HLE_UPDATE_SIGNED=false" >> "$GITHUB_ENV"
    echo "ready=false" >> "$GITHUB_OUTPUT"
    echo "::warning::HLE_SIGNING_BUNDLE is not configured. This PR produces a temporary debug APK, NOT an updatable signed APK."
    exit 0
fi

if [[ "$HLE_SIGNING_BUNDLE" != *"|"* ]]; then
    echo "::error::Invalid HLE_SIGNING_BUNDLE format (expected password|base64keystore)."
    exit 1
fi

store_password="${HLE_SIGNING_BUNDLE%%|*}"
encoded_key="${HLE_SIGNING_BUNDLE#*|}"
if [[ ${#store_password} -lt 20 || ${#encoded_key} -lt 1000 ]]; then
    echo "::error::Invalid personal signing bundle (too short)."
    exit 1
fi

# The bundle is already a GitHub secret; mask the extracted sub-secret too.
echo "::add-mask::$store_password"
keystore_path="$RUNNER_TEMP/hle-personal-updates.p12"
umask 077
printf '%s' "$encoded_key" | base64 --decode > "$keystore_path"
chmod 600 "$keystore_path"

# A wrong secret must fail rather than silently producing a different signature.
if ! keytool -list -storetype PKCS12 -keystore "$keystore_path" \
    -storepass "$store_password" -alias hle > /dev/null 2>&1; then
    echo "::error::Signing bundle is corrupted or password/alias is wrong."
    exit 1
fi

# Pin this installation's certificate. A changed keystore must never be
# uploaded as another "seamlessly updatable" artifact by accident.
expected_sha256="56:CA:78:66:A4:66:1F:A8:48:C5:49:D2:C9:7B:4C:3A:8F:06:E3:46:DA:83:F1:EA:3E:FE:89:89:87:A9:F7:72"
actual_sha256="$(keytool -list -v -storetype PKCS12 -keystore "$keystore_path" \
    -storepass "$store_password" -alias hle 2>/dev/null \
    | sed -nE 's/^[[:space:]]*SHA256: (.*)$/\1/p' | head -1)"
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    echo "::error::Signing certificate changed. Refusing to publish an APK that would break updates."
    exit 1
fi

# Gradle's existing signing config reads these variables, so both debug and
# release APKs can use the SAME signature on every subsequent PR run.
{
    echo "RELEASE_STORE_FILE=$keystore_path"
    echo "RELEASE_STORE_PASSWORD=$store_password"
    echo "RELEASE_KEY_PASSWORD=$store_password"
    echo "RELEASE_KEY_ALIAS=hle"
    echo "HLE_UPDATE_SIGNED=true"
} >> "$GITHUB_ENV"
echo "ready=true" >> "$GITHUB_OUTPUT"

# Erase the secret value from this process after the environment is exported.
unset encoded_key store_password HLE_SIGNING_BUNDLE
echo "Personal update signing key validated; repeatable APK signing enabled."
