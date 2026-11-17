#!/usr/bin/env bash
set -euo pipefail

# Restore the optional bundled HTTPS signing CA for a CI build.
#
# Prints the absolute PEM path on success and nothing when no CA is requested.
# The caller owns --output-dir and must delete it; this helper only writes there.
# Combined format: one unencrypted RSA/EC private key, then the ordered CA path
# (see docs/HTTPS_SIGNING_CA.md). The checks below mirror BundleHttpsCa in
# mobile/build.gradle.kts so bad material fails before Gradle starts instead of
# after a full toolchain setup.
#
# WHEELPLAY_HTTPS_CA_PEM_BASE64  Base64 of the combined PEM (repository secret).
# WHEELPLAY_HTTPS_CA_REQUIRED    1 requires some bundled CA: the secret or the fallback.
# WHEELPLAY_HTTPS_CA_FALLBACK    Only the literal "mitmproxy" enables the pinned
#                                mitmproxy source; the Release workflow sets it by default.

readonly max_bytes=$((2 * 1024 * 1024))
readonly usage='usage: provision-https-ca.sh --output-dir DIR'

# Pinned so the fallback cannot silently follow upstream changes.
readonly fallback_commit=82416906e3ac55b88d215500adf40959e237589d
readonly fallback_url="https://raw.githubusercontent.com/mhils/mitmproxy/$fallback_commit/mitmproxy/certs.py"
readonly fallback_marker='EMUDHRA_CHAIN_PEM = b"""'

output_dir=''
while (($# > 0)); do
  case $1 in
    --output-dir)
      (($# >= 2)) || { printf '%s\n' "provision-https-ca.sh: --output-dir needs a value" >&2; exit 2; }
      output_dir=$2
      shift 2
      ;;
    -h|--help)
      printf '%s\n' "$usage"
      exit 0
      ;;
    *)
      printf '%s\n' "provision-https-ca.sh: unknown argument: $1" "$usage" >&2
      exit 2
      ;;
  esac
done
[[ -n $output_dir ]] || { printf '%s\n' "provision-https-ca.sh: --output-dir is required" "$usage" >&2; exit 2; }

# Actions annotations when running in Actions, plain diagnostics otherwise.
announce() {
  if [[ -n ${GITHUB_ACTIONS:-} ]]; then
    printf '::%s::%s\n' "$1" "$2" >&2
  else
    printf '%s\n' "$2" >&2
  fi
}
fail() { announce error "$1"; exit 1; }

# Bundled CA from the fallback is public material, so the log records its source
# without printing any key bytes.
notify_source() {
  announce notice 'Bundled HTTPS CA comes from the pinned mitmproxy source (public key material, no identity guarantee).'
}

encoded=${WHEELPLAY_HTTPS_CA_PEM_BASE64:-}
required=${WHEELPLAY_HTTPS_CA_REQUIRED:-0}
fallback=${WHEELPLAY_HTTPS_CA_FALLBACK:-}

ca_source=''
if [[ -n $encoded ]]; then
  ca_source=secret
elif [[ $fallback == mitmproxy ]]; then
  ca_source=mitmproxy
elif [[ $required == 1 ]]; then
  fail 'WHEELPLAY_HTTPS_CA_REQUIRED=1 but there is no WHEELPLAY_HTTPS_CA_PEM_BASE64 secret and no mitmproxy fallback.'
else
  announce notice 'No WHEELPLAY_HTTPS_CA_PEM_BASE64 secret; building without a bundled HTTPS signing CA.'
  exit 0
fi

umask 077
mkdir -p -- "$output_dir"
pem_dir=$(cd -- "$output_dir" && pwd -P)
pem=$pem_dir/emudhra-ca.pem

if [[ $ca_source == secret ]]; then
  # Base64 only encodes bytes; GitHub Secrets provides the confidentiality, so the
  # value must never be echoed or written into the checkout.
  # BSD base64 can ignore invalid characters and still exit successfully.
  # Check the alphabet and padding before using the platform decoder.
  encoded=${encoded//[[:space:]]/}
  base64_pattern='^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$'
  if [[ ! $encoded =~ $base64_pattern ]] ||
    ! printf '%s' "$encoded" | base64 --decode > "$pem" 2>/dev/null; then
    rm -f -- "$pem"
    fail 'WHEELPLAY_HTTPS_CA_PEM_BASE64 is not valid Base64. Re-encode the combined PEM file and update the secret.'
  fi
else
  notify_source
  command -v curl > /dev/null || fail 'WHEELPLAY_HTTPS_CA_FALLBACK=mitmproxy needs curl to read the pinned mitmproxy source.'
  download=$pem_dir/certs.py
  if ! curl --fail --silent --show-error --location --max-time 60 \
    --max-filesize 1048576 --output "$download" "$fallback_url"; then
    rm -f -- "$download"
    fail "Could not download $fallback_url for the mitmproxy CA fallback."
  fi
  # Take only the combined PEM constant; the rest of certs.py is irrelevant here.
  awk -v marker="$fallback_marker" '
    index($0, marker) == 1 { capture = 1; next }
    capture && $0 == "\"\"\"" { exit }
    capture { sub(/\r$/, ""); print }
  ' "$download" > "$pem"
  rm -f -- "$download"
  if ! grep -q -- '-----BEGIN PRIVATE KEY-----' "$pem"; then
    rm -f -- "$pem"
    fail "The pinned mitmproxy source no longer contains $fallback_marker at $fallback_commit."
  fi
fi

size=$(wc -c < "$pem")
if ((size == 0)); then
  rm -f -- "$pem"
  fail "Bundled HTTPS CA from the $ca_source is empty."
fi
if ((size > max_bytes)); then
  rm -f -- "$pem"
  fail "HTTPS signing CA PEM is ${size} bytes; the bundled CA is limited to ${max_bytes} bytes (2 MiB)."
fi

key_count=$(grep -c -E '^-----BEGIN (RSA |EC )?PRIVATE KEY-----' "$pem" || true)
cert_count=$(grep -c -E '^-----BEGIN CERTIFICATE-----' "$pem" || true)
if [[ $key_count != 1 ]] || [[ $cert_count == 0 ]] || grep -q 'ENCRYPTED' "$pem"; then
  rm -f -- "$pem"
  fail "HTTPS signing CA PEM from the $ca_source needs exactly one unencrypted RSA/EC private key and at least one certificate."
fi

# Only the path reaches the build; the key stays in the runner's temporary directory.
printf '%s\n' "$pem"
