#!/usr/bin/env bash
set -euo pipefail

# Keep private signing material outside the checkout and remove it on every exit.
: "${ANDROID_KEYSTORE_BASE64:?Set the ANDROID_KEYSTORE_BASE64 Actions secret}"
: "${ANDROID_KEYSTORE_PASSWORD:?Set the ANDROID_KEYSTORE_PASSWORD Actions secret}"
: "${ANDROID_KEY_ALIAS:?Set the ANDROID_KEY_ALIAS Actions secret}"
: "${ANDROID_KEY_PASSWORD:?Set the ANDROID_KEY_PASSWORD Actions secret}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
umask 077
signing_dir=$(mktemp -d "$RUNNER_TEMP/wheelplay-signing.XXXXXX")
trap 'rm -rf -- "$signing_dir"' EXIT
export ANDROID_KEYSTORE_PATH="$signing_dir/wheelplay.jks"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$ANDROID_KEYSTORE_PATH"
unset ANDROID_KEYSTORE_BASE64
# Configuration cache can serialize signing passwords; never save it for signed builds.
./gradlew --no-daemon --no-configuration-cache "$@"
