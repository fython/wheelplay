import test from 'node:test';
import assert from 'node:assert/strict';
import { createImageDebugStats } from '../common/src/main/assets/web/app.js';

test('image stats count committed frames in a rolling second and measure browser decode separately', () => {
  let now = 0;
  const stats = createImageDebugStats(() => now);
  assert.deepEqual(stats.sample(), { fps: null, decodeMs: null, totalFrames: 0 });
  now = 100; stats.frame(92);
  now = 500; stats.frame(480);
  now = 1000;
  assert.deepEqual(stats.sample(), { fps: 2, decodeMs: 20, totalFrames: 2 });
  now = 1100;
  assert.equal(stats.sample().fps, 1);
  now = 1600;
  assert.equal(stats.sample().fps, 0, 'A static image has zero new committed frames');
  stats.reset();
  assert.deepEqual(stats.sample(), { fps: null, decodeMs: null, totalFrames: 0 });
});
