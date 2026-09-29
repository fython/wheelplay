import test from 'node:test';
import assert from 'node:assert/strict';
import { mapPoint, pairingCodeFromUrl } from '../common/src/main/assets/web/app.js';

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
