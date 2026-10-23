#!/usr/bin/env bash
set -euo pipefail

# Runner images and restored caches may already contain some or all required packages.
# Use the local SDK metadata instead of making sdkmanager refresh remote indexes every run.
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
if (( $# == 0 )); then
  echo 'Pass at least one SDK package ID.' >&2
  exit 1
fi

missing=()
for package in "$@"; do
  directory="${ANDROID_HOME}/${package//;/\/}"
  if [[ -f "$directory/package.xml" || -f "$directory/source.properties" ]]; then
    echo "Using installed SDK package: $package"
  else
    missing+=("$package")
  fi
done

if (( ${#missing[@]} )); then
  sdkmanager --sdk_root="$ANDROID_HOME" --install "${missing[@]}"
fi
