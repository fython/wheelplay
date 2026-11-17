import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, statSync, rmSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const provision = fileURLToPath(new URL('../.github/scripts/provision-https-ca.sh', import.meta.url));
const provisionEnv = fileURLToPath(new URL('../.github/scripts/provision-https-ca-env.sh', import.meta.url));
const buildScript = fileURLToPath(new URL('../.github/scripts/build-signed-android.sh', import.meta.url));

const keyPem = `-----BEGIN PRIVATE KEY-----
MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDAAAAAAAAAAAAAAAAAA
-----END PRIVATE KEY-----
`;
const certPem = (commonName) => `-----BEGIN CERTIFICATE-----
MIIB${Buffer.from(commonName).toString('base64')}
-----END CERTIFICATE-----
`;
const combinedPem = keyPem + certPem('ca') + certPem('root');
const encode = value => Buffer.from(value).toString('base64');

// The pinned mitmproxy extraction is a plain awk slice between a marker line
// and the next bare `"""`, so a synthetic source exercises the same path.
const certsPy = keepMarker => `DEFAULT_DHPARAM = b"""
-----BEGIN DH PARAMETERS-----
MIIB
-----END DH PARAMETERS-----
"""

# CA kept for revocation tests.
${keepMarker ? 'EMUDHRA_CHAIN_PEM' : 'SOMETHING_ELSE'} = b"""
${combinedPem}"""
`;

function tempDir(t, prefix) {
  // Match the provisioner's physical paths, including macOS /var symlinks.
  const directory = realpathSync(mkdtempSync(join(tmpdir(), prefix)));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

// A private directory per test keeps parallel runs from sharing fixtures.
function workspace(t) {
  const output = join(tempDir(t, 'https ca '), 'out');
  mkdirSync(output);
  return { output };
}

function curlStub(t, { fixture = certsPy(true), fail = false } = {}) {
  const root = tempDir(t, 'https ca curl ');
  const bin = join(root, 'bin');
  mkdirSync(bin);
  const fixturePath = join(root, 'certs.py');
  writeFileSync(fixturePath, fixture);
  writeFileSync(join(bin, 'curl'), `#!/usr/bin/env bash
set -euo pipefail
out=''
while [[ $# -gt 0 ]]; do
  case $1 in
    --output) out=$2; shift 2;;
    --max-time|--max-filesize) shift 2;;
    --*) shift;;
    *) printf '%s\\n' "$1" >> "$CURL_LOG"; shift;;
  esac
done
[[ "\${CURL_FAIL:-0}" == 1 ]] && { echo 'curl: (22) The requested URL returned error: 404' >&2; exit 22; }
cat "$CURL_FIXTURE" > "$out"
`, { mode: 0o755 });
  return { bin, log: join(root, 'curl.log'), fixturePath, fail };
}

function provisionCa(t, { secret = '', required = '', fallback = '', fixture, curlFails = false } = {}) {
  const stub = curlStub(t, { fixture, fail: curlFails });
  const { output } = workspace(t);
  const result = spawnSync('bash', [provision, '--output-dir', output], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${stub.bin}:${process.env.PATH}`,
      CURL_LOG: stub.log,
      CURL_FIXTURE: stub.fixturePath,
      CURL_FAIL: curlFails ? '1' : '0',
      WHEELPLAY_HTTPS_CA_PEM_BASE64: secret,
      WHEELPLAY_HTTPS_CA_REQUIRED: required,
      WHEELPLAY_HTTPS_CA_FALLBACK: fallback,
    },
  });
  return { ...result, output, stub };
}

function signedBuild(t, { caSecret = '', fallback = '', fixture, curlFails = false, existingCaPath = '' } = {}) {
  const root = tempDir(t, 'https ca build ');
  const stub = curlStub(t, { fixture, fail: curlFails });
  const checkout = join(root, 'checkout');
  const runnerTemp = join(root, 'runner-temp');
  mkdirSync(checkout);
  mkdirSync(runnerTemp);
  const log = join(root, 'gradle.log');
  writeFileSync(join(checkout, 'gradlew'), `#!/usr/bin/env bash
set -euo pipefail
printf 'ca=%s\\n' "\${WHEELPLAY_HTTPS_CA_PEM:-}" > "$CALL_LOG"
if [[ -n \${WHEELPLAY_HTTPS_CA_PEM:-} ]]; then
  printf 'ca-head=%s\\n' "$(head -n 1 "$WHEELPLAY_HTTPS_CA_PEM")" >> "$CALL_LOG"
  printf 'ca-mode=%s\\n' "$(stat ${process.platform === 'darwin' ? "-f '%Lp'" : "-c '%a'"} "$WHEELPLAY_HTTPS_CA_PEM")" >> "$CALL_LOG"
fi
printf 'keystore=%s\\n' "$ANDROID_KEYSTORE_PATH" >> "$CALL_LOG"
printf 'keystore-head=%s\\n' "$(head -c 13 "$ANDROID_KEYSTORE_PATH")" >> "$CALL_LOG"
printf 'args=%s\\n' "$*" >> "$CALL_LOG"
`, { mode: 0o755 });

  const result = spawnSync('bash', [buildScript, ':mobile:assembleRelease', ':mobile:bundleRelease'], {
    cwd: checkout,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${stub.bin}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      CALL_LOG: log,
      CURL_LOG: stub.log,
      CURL_FIXTURE: stub.fixturePath,
      CURL_FAIL: curlFails ? '1' : '0',
      ANDROID_KEYSTORE_BASE64: encode('fake keystore'),
      ANDROID_KEYSTORE_PASSWORD: 'store password',
      ANDROID_KEY_ALIAS: 'wheelplay-release',
      ANDROID_KEY_PASSWORD: 'key password',
      WHEELPLAY_HTTPS_CA_PEM_BASE64: caSecret,
      WHEELPLAY_HTTPS_CA_PEM: existingCaPath,
      WHEELPLAY_HTTPS_CA_FALLBACK: fallback,
    },
  });
  const fields = () => Object.fromEntries(readFileSync(log, 'utf8').trimEnd().split('\n')
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  return { ...result, checkout, runnerTemp, fields, stub };
}

// Mirrors the Debug workflow: provision once, export only the path, keep the file.
function provisionCaEnv(t, { secret = '', required = '', fallback = '', fixture, curlFails = false } = {}) {
  const stub = curlStub(t, { fixture, fail: curlFails });
  const root = tempDir(t, 'https ca env ');
  const runnerTemp = join(root, 'runner-temp');
  mkdirSync(runnerTemp);
  const githubEnv = join(root, 'github-env');
  writeFileSync(githubEnv, '');
  const result = spawnSync('bash', [provisionEnv], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${stub.bin}:${process.env.PATH}`,
      RUNNER_TEMP: runnerTemp,
      GITHUB_ENV: githubEnv,
      CURL_LOG: stub.log,
      CURL_FIXTURE: stub.fixturePath,
      CURL_FAIL: curlFails ? '1' : '0',
      WHEELPLAY_HTTPS_CA_PEM_BASE64: secret,
      WHEELPLAY_HTTPS_CA_REQUIRED: required,
      WHEELPLAY_HTTPS_CA_FALLBACK: fallback,
    },
  });
  return { ...result, runnerTemp, githubEnv, stub };
}

test('an absent secret is a silent no-op and never reaches the network', t => {
  const r = provisionCa(t);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, '');
  assert.deepEqual(readdirSync(r.output), []);
  assert.equal(existsSync(r.stub.log), false);
});

test('a required CA that is absent fails the step', t => {
  const r = provisionCa(t, { required: '1' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /WHEELPLAY_HTTPS_CA_REQUIRED=1 but there is no WHEELPLAY_HTTPS_CA_PEM_BASE64 secret and no mitmproxy fallback/);
  assert.deepEqual(readdirSync(r.output), []);
  assert.equal(existsSync(r.stub.log), false);
});

test('the secret is decoded into an owner-only file and only its path is printed', t => {
  const r = provisionCa(t, { secret: encode(combinedPem) });
  assert.equal(r.status, 0, r.stderr);
  const pem = r.stdout.trim();
  assert.equal(pem, join(r.output, 'emudhra-ca.pem'));
  assert.equal(readFileSync(pem, 'utf8'), combinedPem);
  assert.equal(statSync(pem).mode & 0o777, 0o600);
  assert.doesNotMatch(r.stderr, /PRIVATE KEY/);
});

test('Base64 with line wrapping and surrounding whitespace is accepted', t => {
  const wrapped = encode(combinedPem).replace(/(.{64})/g, '$1\n');
  const r = provisionCa(t, { secret: `\n${wrapped}\n` });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(readFileSync(r.stdout.trim(), 'utf8'), combinedPem);
});

test('a value that is not Base64 fails with an actionable message', t => {
  const r = provisionCa(t, { secret: 'not base64 !!!' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /is not valid Base64/);
  assert.deepEqual(readdirSync(r.output), []);
});

test('invalid characters and padding cannot be ignored in an otherwise valid CA', async t => {
  const valid = encode(combinedPem);
  for (const [name, secret] of Object.entries({
    punctuation: valid.slice(0, 8) + '!!!' + valid.slice(8),
    middlePadding: valid.slice(0, 8) + '=' + valid.slice(8),
    excessPadding: valid + '====',
    incompleteQuartet: valid + 'A',
  })) {
    await t.test(name, t => {
      const r = provisionCa(t, { secret });
      assert.equal(r.status, 1);
      assert.match(r.stderr, /is not valid Base64/);
      assert.equal(r.stdout, '');
      assert.deepEqual(readdirSync(r.output), []);
    });
  }
});

test('encrypted keys, multiple keys and missing certificates are rejected', async t => {
  const cases = {
    encrypted: keyPem.replace('PRIVATE KEY', 'ENCRYPTED PRIVATE KEY') + certPem('ca'),
    'two keys': keyPem + keyPem + certPem('ca'),
    'no certificate': keyPem,
  };
  for (const [name, pem] of Object.entries(cases)) {
    await t.test(name, () => {
      const r = provisionCa(t, { secret: encode(pem) });
      assert.equal(r.status, 1);
      assert.match(r.stderr, /exactly one unencrypted RSA\/EC private key and at least one certificate/);
      assert.deepEqual(readdirSync(r.output), []);
    });
  }
});

test('the 2 MiB bundled limit matches the Gradle task', t => {
  // The over-limit branch cannot be reached through this harness: one environment
  // string may not exceed the Linux 128 KiB execve limit, so a decoded 2 MiB
  // secret cannot be passed. Keep the two constants from drifting instead.
  const helper = readFileSync(provision, 'utf8');
  const gradle = readFileSync(new URL('../mobile/build.gradle.kts', import.meta.url), 'utf8');
  assert.match(helper, /max_bytes=\$\(\(2 \* 1024 \* 1024\)\)/);
  assert.match(gradle, /source\.length\(\) in 1\.\.\(2 \* 1024 \* 1024\)\.toLong\(\)/);
});

test('a whitespace-only secret decodes to an empty file and fails', t => {
  const r = provisionCa(t, { secret: '   \n\t' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /from the secret is empty/);
  assert.deepEqual(readdirSync(r.output), []);
});

test('the mitmproxy fallback stays off unless it is explicitly requested', t => {
  const r = provisionCa(t, { fallback: 'yes' });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, '');
  assert.equal(existsSync(r.stub.log), false);
});

test('the mitmproxy fallback bundles the pinned constant and records its source', t => {
  const r = provisionCa(t, { fallback: 'mitmproxy' });
  assert.equal(r.status, 0, r.stderr);
  const pem = r.stdout.trim();
  assert.equal(readFileSync(pem, 'utf8'), combinedPem);
  assert.equal(statSync(pem).mode & 0o777, 0o600);
  assert.match(r.stderr, /public key material, no identity guarantee/);
  assert.match(readFileSync(r.stub.log, 'utf8'), /^https:\/\/raw\.githubusercontent\.com\/mhils\/mitmproxy\/82416906e3ac55b88d215500adf40959e237589d\//);
  // The downloaded source is removed; only the PEM reaches Gradle.
  assert.deepEqual(readdirSync(r.output), ['emudhra-ca.pem']);
});

test('the fallback fails when the pinned source no longer has the constant', t => {
  const r = provisionCa(t, { fallback: 'mitmproxy', fixture: certsPy(false) });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /no longer contains EMUDHRA_CHAIN_PEM = b/);
  assert.deepEqual(readdirSync(r.output), []);
});

test('the fallback fails when the download fails', t => {
  const r = provisionCa(t, { fallback: 'mitmproxy', curlFails: true });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /Could not download .*mitmproxy/);
  assert.deepEqual(readdirSync(r.output), []);
});

test('a required CA is satisfied by the fallback without touching the secret', t => {
  const r = provisionCa(t, { required: '1', fallback: 'mitmproxy' });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(readFileSync(r.stdout.trim(), 'utf8'), combinedPem);
  assert.match(r.stderr, /public key material/);
});

test('the signed build exports the temporary CA path and removes every secret file', t => {
  const r = signedBuild(t, { caSecret: encode(combinedPem) });
  assert.equal(r.status, 0, r.stderr);
  const fields = r.fields();

  assert.match(fields.ca, new RegExp(`^${r.runnerTemp.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/wheelplay-https-ca\\.`));
  assert.equal(fields['ca-head'], '-----BEGIN PRIVATE KEY-----');
  assert.equal(fields['ca-mode'], '600');
  assert.match(fields.keystore, /wheelplay-signing\.[^/]+\/wheelplay\.jks$/);
  assert.equal(fields['keystore-head'], 'fake keystore');
  assert.equal(fields.args, '--no-daemon --no-configuration-cache :mobile:assembleRelease :mobile:bundleRelease');
  // Nothing may survive the step, and nothing may land in the checkout.
  assert.deepEqual(readdirSync(r.runnerTemp), []);
  assert.deepEqual(readdirSync(r.checkout).sort(), ['gradlew']);
});

test('the signed build can bundle the fallback CA', t => {
  const r = signedBuild(t, { fallback: 'mitmproxy' });
  assert.equal(r.status, 0, r.stderr);
  const fields = r.fields();
  assert.match(fields.ca, /wheelplay-https-ca\.[^/]+\/emudhra-ca\.pem$/);
  assert.equal(fields['ca-head'], '-----BEGIN PRIVATE KEY-----');
  assert.match(r.stderr, /public key material/);
  assert.deepEqual(readdirSync(r.runnerTemp), []);
  assert.deepEqual(readdirSync(r.checkout).sort(), ['gradlew']);
});

test('the signed build runs without any CA secret or fallback', t => {
  const r = signedBuild(t);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.fields().ca, '');
  assert.deepEqual(readdirSync(r.runnerTemp), []);
  assert.equal(existsSync(join(r.checkout, 'wheelplay-https-ca')), false);
});

test('the Debug provisioning step exports only the path and keeps the material', t => {
  const r = provisionCaEnv(t, { required: '1', fallback: 'mitmproxy' });
  assert.equal(r.status, 0, r.stderr);
  const exported = readFileSync(r.githubEnv, 'utf8');
  assert.match(exported, /^WHEELPLAY_HTTPS_CA_PEM=.*wheelplay-https-ca\.[^/]+\/emudhra-ca\.pem\n$/);
  const pem = exported.trim().slice('WHEELPLAY_HTTPS_CA_PEM='.length);
  // The workflow cleans this up after the artifacts are uploaded.
  assert.equal(readFileSync(pem, 'utf8'), combinedPem);
  assert.equal(readdirSync(r.runnerTemp).length, 1);
  assert.match(r.stderr, /public key material/);
});

test('the Debug provisioning step exports nothing when no CA is configured', t => {
  const r = provisionCaEnv(t);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(readFileSync(r.githubEnv, 'utf8'), '');
  assert.deepEqual(readdirSync(r.runnerTemp), []);
  assert.equal(existsSync(r.stub.log), false);
});

test('the Debug provisioning step fails on a malformed secret and cleans up', t => {
  const r = provisionCaEnv(t, { secret: 'not base64 !!!', required: '1' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /is not valid Base64/);
  assert.equal(readFileSync(r.githubEnv, 'utf8'), '');
  assert.deepEqual(readdirSync(r.runnerTemp), []);
});

test('the signed build reuses a CA that another step already provisioned', t => {
  const directory = tempDir(t, 'https ca pre ');
  const existing = join(directory, 'provided.pem');
  writeFileSync(existing, combinedPem);
  const r = signedBuild(t, { fallback: 'mitmproxy', existingCaPath: existing });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.fields().ca, existing);
  // No second download, and the caller's directory is left untouched.
  assert.equal(existsSync(r.stub.log), false);
  assert.equal(readFileSync(existing, 'utf8'), combinedPem);
});
