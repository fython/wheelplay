import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('../.github/scripts/run-android-runtime-tests.sh', import.meta.url));
function run(t, mode) {
  const root = mkdtempSync(join(tmpdir(), 'android runtime '));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const bin = join(root, 'bin'); mkdirSync(bin);
  writeFileSync(join(bin, 'adb'), `#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$CALL_LOG"
case "$*" in
  *logcat*) echo diagnostic-logcat; exit 0;;
  *dumpsys*) echo diagnostic-services; exit 0;;
  *install*) [[ "$MODE" != install-error ]] || exit 17; exit 0;;
  *am\\ instrument*)
    if [[ "$MODE" == browser-failure && "$*" == *BrowserPageRuntimeTest* ]]; then echo 'FAILURES!!!'; exit 0; fi
    if [[ "$MODE" == crash ]]; then echo 'INSTRUMENTATION_FAILED: Process crashed.'; exit 0; fi
    if [[ "$MODE" == transport-error ]]; then echo 'OK (1 test)'; exit 42; fi
    echo 'OK (1 test)'; exit 0;;
esac
`, { mode: 0o755 });
  const result = spawnSync('bash', [script], { cwd: root, encoding: 'utf8',
    env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, MODE: mode, CALL_LOG: join(root, 'calls') } });
  return { ...result, calls: readFileSync(join(root, 'calls'), 'utf8'),
    logcat: readFileSync(join(root, 'runtime-logcat.log'), 'utf8'),
    services: readFileSync(join(root, 'runtime-services.log'), 'utf8') };
}

test('runs both real instrumentation commands and preserves diagnostics on success', t => {
  const r = run(t, 'success'); assert.equal(r.status, 0, r.stderr);
  assert.match(r.calls, /BrowserPageRuntimeTest/); assert.match(r.calls, /PluginSurfaceIntegrationTest/);
  assert.match(r.logcat, /diagnostic-logcat/); assert.match(r.services, /diagnostic-services/);
});
test('zero-exit JUnit failure fails CI but still runs the other suite', t => {
  const r = run(t, 'browser-failure'); assert.equal(r.status, 1);
  assert.match(r.calls, /PluginSurfaceIntegrationTest/); assert.match(r.stdout, /::error::/);
});
test('instrumentation crashes without a JUnit summary fail CI', t => {
  assert.equal(run(t, 'crash').status, 1);
});
test('a failed adb command cannot be hidden by a success-looking test summary', t => {
  assert.equal(run(t, 'transport-error').status, 1);
});
test('install failures retain their exit status and still collect diagnostics', t => {
  const r = run(t, 'install-error'); assert.equal(r.status, 17);
  assert.match(r.logcat, /diagnostic-logcat/); assert.doesNotMatch(r.calls, /am instrument/);
});
