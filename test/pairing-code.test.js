import test from 'node:test';
import assert from 'node:assert/strict';
import { createPairingCodeInput } from '../common/src/main/assets/web/app.js';

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
