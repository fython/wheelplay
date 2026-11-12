import test from 'node:test';
import assert from 'node:assert/strict';
import { createPairingCodeInput, placePairingKeypad } from '../common/src/main/assets/web/app.js';

function fixture() {
  let focused = -1;
  const inputs = Array.from({ length: 6 }, (_, index) => ({
    value: '', handlers: {}, focus() { focused = index; }, select() {},
    addEventListener(type, handler) { this.handlers[type] = handler; },
  }));
  const value = { value: '' }, control = createPairingCodeInput(inputs, value);
  const input = (index, text) => { inputs[index].value = text; inputs[index].handlers.input(); };
  const key = (index, key) => inputs[index].handlers.keydown({ key, preventDefault() {} });
  const paste = (index, text) => inputs[index].handlers.paste({ clipboardData: { getData: () => text }, preventDefault() {} });
  return { inputs, value, control, input, key, paste, focused: () => focused };
}

test('PIN fields preserve leading zeroes, filter non-digits and advance focus', () => {
  const f = fixture();
  f.input(0, '0'); assert.equal(f.focused(), 1);
  f.input(1, 'x'); assert.equal(f.inputs[1].value, '');
  for (let i = 1; i < 6; i++) f.input(i, String(i));
  assert.equal(f.value.value, '012345');
  assert.equal(f.focused(), 5);
});

test('paste and autofill distribute a full code from any field and partial paste replaces in place', () => {
  const f = fixture();
  f.paste(3, '01 23-45');
  assert.equal(f.value.value, '012345');
  assert.deepEqual(f.inputs.map(input => input.value), ['0', '1', '2', '3', '4', '5']);
  f.paste(2, '98'); assert.equal(f.value.value, '019845');
  f.input(0, '654321'); assert.equal(f.value.value, '654321');
});

test('backspace and keyboard navigation support correction without submitting', () => {
  const f = fixture(); f.control.set('012345');
  f.input(3, ''); f.key(3, 'Backspace');
  assert.equal(f.inputs[2].value, ''); assert.equal(f.focused(), 2);
  f.key(2, 'ArrowLeft'); assert.equal(f.focused(), 1);
  f.key(1, 'ArrowRight'); assert.equal(f.focused(), 2);
  f.key(2, 'Home'); assert.equal(f.focused(), 0);
  f.key(0, 'End'); assert.equal(f.focused(), 5);
});

test('remembered pairing disables hidden required inputs and link prefill replaces every digit', () => {
  const f = fixture(); f.control.set('012345'); f.control.set('987');
  assert.deepEqual(f.inputs.map(input => input.value), ['9', '8', '7', '', '', '']);
  f.control.enabled(false);
  assert.ok(f.inputs.every(input => input.disabled && !input.required));
  f.control.enabled(true);
  assert.ok(f.inputs.every(input => !input.disabled && input.required));
});

test('virtual digits, backspace and clear share the same six-digit value and validation', () => {
  const f = fixture();
  for (const digit of '012345') f.control.digit(digit);
  assert.equal(f.value.value, '012345'); assert.equal(f.control.complete, true);
  f.control.backspace();
  assert.equal(f.value.value, '01234'); assert.equal(f.control.complete, false);
  assert.equal(f.focused(), 5);
  f.control.backspace(); assert.equal(f.value.value, '0123'); assert.equal(f.focused(), 4);
  f.control.clear(); assert.equal(f.value.value, ''); assert.equal(f.focused(), 0);
  f.control.enabled(false); f.control.digit('9');
  assert.equal(f.value.value, '');
});

test('hardware digits and Delete work without making the fields editable', () => {
  const f = fixture();
  for (let i = 0; i < 6; i++) f.key(i, String(i));
  assert.equal(f.value.value, '012345');
  f.key(2, 'Delete'); assert.equal(f.inputs[2].value, '');
  f.key(2, '9'); assert.equal(f.value.value, '019345');
});

test('keypad chooses an unobstructed side and stays inside the visible viewport', () => {
  const viewport = { left: 0, top: 0, width: 800, height: 360 }, size = { width: 304, height: 264 };
  const cases = [
    [{ left: 40, right: 340, top: 100, bottom: 158 }, viewport, size, 'right'],
    [{ left: 450, right: 750, top: 100, bottom: 158 }, viewport, size, 'left'],
    [{ left: 40, right: 340, top: 100, bottom: 158 }, { ...viewport, height: 1000 }, size, 'below'],
    [{ left: 40, right: 340, top: 700, bottom: 758 }, { ...viewport, height: 1000 }, size, 'above'],
    [{ left: 135, right: 485, top: 112, bottom: 170 }, { left: 120, top: 90, width: 390, height: 300 }, { width: 304, height: 200 }, 'below'],
  ];
  for (const [anchor, view, panel, expected] of cases) {
    const position = placePairingKeypad(anchor, view, panel);
    assert.equal(position.placement, expected);
    assert.ok(position.left >= view.left + 12 && position.top >= view.top + 12);
    assert.ok(position.left + panel.width <= view.left + view.width - 12);
    assert.ok(position.top + panel.height <= view.top + view.height - 12);
    assert.ok(position.left >= anchor.right || position.left + panel.width <= anchor.left ||
      position.top >= anchor.bottom || position.top + panel.height <= anchor.top);
  }
});

test('keypad requests scrolling when the anchor is clipped or no full-size placement fits', () => {
  const view = { left: 0, top: 0, width: 320, height: 320 };
  assert.equal(placePairingKeypad({ left: 12, right: 308, top: 280, bottom: 338 }, view, { width: 296, height: 230 }), null);
  assert.equal(placePairingKeypad({ left: 12, right: 308, top: 130, bottom: 188 }, view, { width: 296, height: 270 }), null);
  assert.deepEqual(placePairingKeypad({ left: 12, right: 308, top: 12, bottom: 70 }, view, { width: 296, height: 230 }),
    { left: 12, top: 78, placement: 'below' });
});
