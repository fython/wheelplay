import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('../.github/scripts/install-android-packages.sh', import.meta.url));
const packages = ['platforms;android-36', 'build-tools;36.0.0', 'ndk;28.2.13676358', 'cmake;3.22.1'];

function sdkFixture(t) {
  // Include spaces to check SDK paths and package arguments are kept intact.
  const directory = mkdtempSync(join(tmpdir(), 'wheelplay sdk '));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const sdk = join(directory, 'Android SDK'), bin = join(directory, 'bin'), log = join(directory, 'sdkmanager.log');
  mkdirSync(sdk); mkdirSync(bin);
  writeFileSync(join(bin, 'sdkmanager'), `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" > "$SDK_CALL_LOG"
if [[ "\${MOCK_SDKMANAGER_FAIL:-0}" == 1 ]]; then exit 7; fi
shift 2
for package in "$@"; do
  directory="$ANDROID_HOME/\${package//;/\\/}"
  mkdir -p "$directory"
  printf 'installed' > "$directory/package.xml"
done
`, { mode: 0o755 });
  return {
    sdk, log,
    installed(packageId) {
      const directory = join(sdk, ...packageId.split(';'));
      mkdirSync(directory, { recursive: true });
      writeFileSync(join(directory, 'package.xml'), 'preinstalled');
    },
    run(fail = false) {
      return spawnSync('bash', [script, ...packages], {
        encoding: 'utf8',
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, ANDROID_HOME: sdk,
          SDK_CALL_LOG: log, MOCK_SDKMANAGER_FAIL: fail ? '1' : '0' },
      });
    },
    calls() { return readFileSync(log, 'utf8').trimEnd().split('\n'); },
  };
}

test('SDK cold start installs all packages and a warm run skips sdkmanager entirely', t => {
  const f = sdkFixture(t);
  assert.equal(f.run().status, 0);
  assert.deepEqual(f.calls(), [`--sdk_root=${f.sdk}`, '--install', ...packages]);
  rmSync(f.log);
  assert.equal(f.run().status, 0);
  assert.equal(existsSync(f.log), false);
});

test('a partial SDK cache installs only missing packages and preserves preinstalled metadata', t => {
  const f = sdkFixture(t);
  f.installed(packages[0]); f.installed(packages[1]);
  assert.equal(f.run().status, 0);
  assert.deepEqual(f.calls(), [`--sdk_root=${f.sdk}`, '--install', ...packages.slice(2)]);
  assert.equal(readFileSync(join(f.sdk, 'platforms', 'android-36', 'package.xml'), 'utf8'), 'preinstalled');
});

test('SDK packages unpacked from official archives do not require sdkmanager metadata', t => {
  const f = sdkFixture(t);
  for (const packageId of packages) {
    const directory = join(f.sdk, ...packageId.split(';'));
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'source.properties'), 'Pkg.Revision = 1');
  }
  assert.equal(f.run().status, 0);
  assert.equal(existsSync(f.log), false);
});

test('sdkmanager failures fail the setup step without marking missing packages installed', t => {
  const f = sdkFixture(t);
  assert.equal(f.run(true).status, 7);
  assert.equal(existsSync(join(f.sdk, 'ndk', '28.2.13676358', 'package.xml')), false);
});
