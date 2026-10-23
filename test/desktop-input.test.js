import test from 'node:test';
import assert from 'node:assert/strict';
import { createDesktopInput } from '../common/src/main/assets/web/desktop-input.js';

function fixture() {
  const listeners = {}, sent = [];
  let enabled = true, ready = true, focuses = 0;
  const surface = { addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
    focus() { focuses++; }, setPointerCapture() {} };
  const input = createDesktopInput({ send: (data, kind) => sent.push({ ...data, delivery: kind }),
    pointerEnabled: () => enabled, keyboardEnabled: () => enabled, interactive: () => ready,
    point: event => ({ x: event.clientX ?? 0.5, y: event.clientY ?? 0.5 }) });
  input.bind(surface);
  return { input, sent, get focuses() { return focuses; }, enable(value) { enabled = value; },
    ready(value) { ready = value; }, fire(type, props = {}) {
      const event = { pointerType: 'mouse', buttons: 0, preventDefault() {}, ...props };
      for (const fn of listeners[type] || []) fn(event);
    } };
}
test('capabilities and live lease gate mouse, keys and scrolling', () => {
  const f = fixture();
  for (const disabled of ['capability', 'lease']) {
    f.enable(disabled !== 'capability'); f.ready(disabled !== 'lease');
    f.fire('pointerdown', { buttons: 1 }); f.fire('keydown', { key: 'a', code: 'KeyA' });
    f.fire('wheel', { deltaY: 1, deltaX: 0 });
  }
  assert.deepEqual(f.sent, []);
});
test('mouse keeps hover, right drag and release boundaries, touch is handled separately', () => {
  const f = fixture();
  f.fire('pointermove'); f.fire('pointerdown', { buttons: 2 }); f.fire('pointermove', { buttons: 2 });
  f.fire('pointerup'); f.fire('pointerdown', { pointerType: 'touch', buttons: 1 });
  assert.deepEqual(f.sent.map(e => [e.kind, e.buttons, e.delivery]), [
    ['pointer',0,'move'], ['pointer',2,'boundary'], ['pointer',2,'move'], ['pointer',0,'boundary'] ]);
  assert.equal(f.input.active, false);
});
test('keyup releases the original case after modifier changes; blur releases held keys', () => {
  const f = fixture();
  f.fire('keydown', { key: 'Shift', code: 'ShiftLeft' });
  f.fire('keydown', { key: 'A', code: 'KeyA' });
  f.fire('keyup', { key: 'Shift', code: 'ShiftLeft' });
  f.fire('keyup', { key: 'a', code: 'KeyA' });
  assert.equal(f.sent.at(-1).key, 'A'); assert.equal(f.sent.at(-1).down, false);
  f.fire('keydown', { key: 'Control', code: 'ControlLeft' });
  f.input.heartbeat(); assert.equal(f.sent.at(-1).kind, 'keepalive');
  f.fire('blur'); assert.equal(f.sent.at(-1).kind, 'release'); assert.equal(f.input.active, false);
});
test('reset on a new session discards stale keys without sending to the replacement', () => {
  const f = fixture();
  f.fire('keydown', { key: 'a', code: 'KeyA' }); f.input.release(false);
  f.fire('keydown', { key: 'a', code: 'KeyA', repeat: true });
  f.fire('keyup', { key: 'a', code: 'KeyA' }); f.input.heartbeat();
  assert.equal(f.sent.length, 1);
});
test('wheel sends bounded direction and composing keys are ignored', () => {
  const f = fixture();
  f.fire('wheel', { deltaX: 500, deltaY: -1000 });
  assert.equal(f.sent[0].dx, 1); assert.equal(f.sent[0].dy, -1);
  f.fire('keydown', { key: 'Dead' }); f.fire('keydown', { key: '中', isComposing: true });
  assert.equal(f.sent.length, 1);
});

test('releasing one of two modifier keys keeps the other held', () => {
  const f = fixture();
  f.fire('keydown', { key: 'Shift', code: 'ShiftLeft' });
  f.fire('keydown', { key: 'Shift', code: 'ShiftRight' });
  f.fire('keyup', { key: 'Shift', code: 'ShiftLeft' });
  assert.equal(f.sent.length, 1); assert.equal(f.input.active, true);
  f.fire('keyup', { key: 'Shift', code: 'ShiftRight' });
  assert.equal(f.sent.length, 2); assert.equal(f.sent[1].down, false);
});
