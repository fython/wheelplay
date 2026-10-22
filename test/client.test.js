import test from 'node:test';
import assert from 'node:assert/strict';
import { mapPoint, pairingCodeFromUrl, unzoomedDisplayRect, browserViewportSize } from '../common/src/main/assets/web/app.js';

test('landscape pillarbox is excluded and video center maps correctly', () => {
  const rect = { left: 10, top: 80, width: 1600, height: 600 };
  assert.equal(mapPoint(20, 200, rect, 1280, 720), null);
  assert.deepEqual(mapPoint(810, 380, rect, 1280, 720), { x: .5, y: .5 });
});
test('portrait letterbox is excluded', () => {
  const rect = { left: 0, top: 0, width: 600, height: 900 };
  assert.equal(mapPoint(300, 20, rect, 1280, 720), null);
  assert.deepEqual(mapPoint(300, 450, rect, 1280, 720), { x: .5, y: .5 });
});
test('a captured drag clamps to frame edges', () => {
  const rect = { left: 0, top: 0, width: 1280, height: 720 };
  assert.deepEqual(mapPoint(-100, 900, rect, 1280, 720, true), { x: 0, y: 1 });
  assert.deepEqual(mapPoint(1280, 720, rect, 1280, 720), { x: 1, y: 1 });
});
test('zero sized frame cannot send a touch', () => {
  assert.equal(mapPoint(0, 0, { left:0,top:0,width:0,height:0 }, 1280, 720), null);
});

test('quick browser links accept only an exact six-digit pairing code', () => {
  assert.equal(pairingCodeFromUrl('?code=123456'), '123456');
  assert.equal(pairingCodeFromUrl('?other=1&code=000042'), '000042');
  assert.equal(pairingCodeFromUrl('?code=12345'), null);
  assert.equal(pairingCodeFromUrl('?code=1234567'), null);
  assert.equal(pairingCodeFromUrl('?code=12x456'), null);
});

test('ignoring browser zoom fits the visible area and preserves normalized touches', () => {
  const rect = unzoomedDisplayRect({ top: 0 }, { width: 640, height: 480, scale: 1.5, offsetLeft: 160, offsetTop: 90 });
  assert.deepEqual(rect, { left: 160, top: 90, width: 640, height: 480, scale: 1.5 });
  assert.deepEqual(mapPoint(480, 330, rect, 1920, 1440), { x: 0.5, y: 0.5 });
  assert.deepEqual(mapPoint(800, 570, rect, 1920, 1440), { x: 1, y: 1 });
  const withToolbar = unzoomedDisplayRect({ top: 80 }, { width: 640, height: 480, scale: 1.5 });
  assert.equal(withToolbar.height * withToolbar.scale, 600);
  assert.equal(unzoomedDisplayRect({ top: 0 }, null), null);
  assert.equal(unzoomedDisplayRect({ top: 0 }, { width: 640, height: 480, scale: 0 }), null);
});

test('viewport negotiation preserves aspect while fitting Android dimension limits', () => {
  assert.deepEqual(browserViewportSize(960, 304), { width: 1010, height: 320 });
  assert.deepEqual(browserViewportSize(160, 320), { width: 320, height: 640 });
  assert.deepEqual(browserViewportSize(16384, 8192), { width: 8192, height: 4096 });
  assert.deepEqual(browserViewportSize(960, 336), { width: 960, height: 336 });
  for (const [w, h] of [[0, 720], [NaN, 720], [Infinity, 720], [960, -1], [1600, 300], [300, 1000]]) {
    assert.equal(browserViewportSize(w, h), null);
  }
  for (const [w, h] of [[1283.6, 320.9], [320.9, 641.8], [8191.9, 2047.98]]) {
    const size = browserViewportSize(w, h);
    assert.ok(size.width >= 320 && size.width <= 8192);
    assert.ok(size.height >= 320 && size.height <= 8192);
    assert.ok(size.width / size.height >= 0.5 && size.width / size.height <= 4);
    assert.equal(size.width % 2 + size.height % 2, 0);
  }
});
