#!/usr/bin/env bash
set -euo pipefail

# Provision the bundled HTTPS signing CA for a build step that is not
# build-signed-android.sh (the Debug workflow), and export its path to later steps.
#
# Writes WHEELPLAY_HTTPS_CA_PEM into $GITHUB_ENV and leaves the material in
# $RUNNER_TEMP; the workflow must delete it afterwards (see the cleanup step in
# debug-build.yml). No CA is exported when none was requested.
#
# WHEELPLAY_HTTPS_CA_PEM_BASE64 / _REQUIRED / _FALLBACK behave exactly as in
# provision-https-ca.sh; see docs/HTTPS_SIGNING_CA.md.

: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
: "${GITHUB_ENV:?GITHUB_ENV must be set}"

announce() {
  if [[ -n ${GITHUB_ACTIONS:-} ]]; then
    printf '::%s::%s\n' "$1" "$2" >&2
  else
    printf '%s\n' "$2" >&2
  fi
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
umask 077
ca_dir=$(mktemp -d "$RUNNER_TEMP/wheelplay-https-ca.XXXXXX")

if ! pem=$(bash "$script_dir/provision-https-ca.sh" --output-dir "$ca_dir"); then
  rm -rf -- "$ca_dir"
  exit 1
fi

if [[ -z $pem ]]; then
  rm -rf -- "$ca_dir"
  announce notice 'No bundled HTTPS CA for this build.'
  exit 0
fi

# Only the path is exported; the key stays in the runner's temporary directory.
printf 'WHEELPLAY_HTTPS_CA_PEM=%s\n' "$pem" >> "$GITHUB_ENV"
