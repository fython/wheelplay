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
ca_dir=''
cleanup() {
  rm -rf -- "$signing_dir"
  [[ -z $ca_dir ]] || rm -rf -- "$ca_dir"
}
trap cleanup EXIT
export ANDROID_KEYSTORE_PATH="$signing_dir/wheelplay.jks"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$ANDROID_KEYSTORE_PATH"
unset ANDROID_KEYSTORE_BASE64

# Optional bundled HTTPS signing CA. A caller may already have provisioned one
# (for example the Debug workflow exports WHEELPLAY_HTTPS_CA_PEM into GITHUB_ENV);
# otherwise this helper validates the secret or the fallback and reports a
# temporary path that the trap above deletes together with the keystore.
want_ca=0
if [[ -n ${WHEELPLAY_HTTPS_CA_PEM_BASE64:-} ]]; then want_ca=1; fi
if [[ ${WHEELPLAY_HTTPS_CA_REQUIRED:-0} == 1 ]]; then want_ca=1; fi
if [[ ${WHEELPLAY_HTTPS_CA_FALLBACK:-} == mitmproxy ]]; then want_ca=1; fi
if [[ -z ${WHEELPLAY_HTTPS_CA_PEM:-} && $want_ca == 1 ]]; then
  ca_dir=$(mktemp -d "$RUNNER_TEMP/wheelplay-https-ca.XXXXXX")
  script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
  WHEELPLAY_HTTPS_CA_PEM=$(bash "$script_dir/provision-https-ca.sh" --output-dir "$ca_dir")
  export WHEELPLAY_HTTPS_CA_PEM
  unset WHEELPLAY_HTTPS_CA_PEM_BASE64
fi

# Configuration cache can serialize signing passwords; never save it for signed builds.
./gradlew --no-daemon --no-configuration-cache "$@"
