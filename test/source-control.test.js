import test from 'node:test';
import assert from 'node:assert/strict';
import { sourcePanel } from '../common/src/main/assets/web/source-control.js';
const base = { version: 1, selectedName: 'CarPlay', activeId: 'plugin', activeName: 'CarLink', phase: 'running', action: 'stop' };
test('source readiness without a browser frame never offers connect', () => {
  const panel = sourcePanel(base, { visible: false });
  assert.equal(panel.name, 'CarLink'); assert.equal(panel.action, 'stop'); assert.equal(panel.retry, true);
  assert.equal(panel.title, 'web_source_receiving');
  assert.equal(sourcePanel(base, { visible: false }, true).title, 'web_source_media_failed');
});
test('connecting, cleanup and unsupported servers do not offer media retry or implicit CarPlay', () => {
  assert.equal(sourcePanel({ ...base, phase: 'connecting' }, {}).label, 'web_source_cancel');
  assert.equal(sourcePanel({ ...base, phase: 'connecting' }, {}).retry, false);
  for (const control of [null, { ...base, phase: 'stopping' }, { ...base, cleanup: 'unconfirmed' }]) {
    assert.equal(sourcePanel(control, {}).action, ''); assert.equal(sourcePanel(control, {}).retry, false);
  }
});
