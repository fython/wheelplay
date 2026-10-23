// Desktop events share the versioned control lease with touch; no remote clipboard or IME capture.
export function createDesktopInput({ send, pointerEnabled, keyboardEnabled, interactive, point }) {
  const keys = new Map();
  let buttons = 0;
  const emit = (event, kind) => send({ type: 'input', ...event }, kind);
  function release(transmit = true) {
    const held = buttons !== 0 || keys.size !== 0;
    buttons = 0; keys.clear();
    if (transmit && held) emit({ kind: 'release' });
  }
  function bind(surface) {
    surface.tabIndex = 0;
    const mouse = (event, boundary = false) => {
      if (!pointerEnabled() || !interactive() || event.pointerType !== 'mouse') return;
      const position = point(event, surface, buttons !== 0);
      if (!position) return;
      event.preventDefault();
      const next = event.buttons & 7;
      const changed = next !== buttons; buttons = next;
      emit({ kind: 'pointer', ...position, buttons }, boundary || changed ? 'boundary' : 'move');
    };
    surface.addEventListener('pointerdown', event => {
      if (interactive() && keyboardEnabled()) surface.focus({ preventScroll: true });
      if (!pointerEnabled() || !interactive() || event.pointerType !== 'mouse') return;
      surface.setPointerCapture(event.pointerId); mouse(event, true);
    });
    surface.addEventListener('pointermove', event => mouse(event));
    surface.addEventListener('pointerup', event => mouse(event, true));
    surface.addEventListener('pointercancel', () => release());
    surface.addEventListener('lostpointercapture', () => { if (buttons) release(); });
    surface.addEventListener('wheel', event => {
      if (!pointerEnabled() || !interactive()) return;
      const position = point(event, surface, false); if (!position) return;
      event.preventDefault();
      emit({ kind: 'scroll', ...position, dx: Math.sign(event.deltaX), dy: Math.sign(event.deltaY) });
    }, { passive: false });
    surface.addEventListener('keydown', event => {
      if (!keyboardEnabled() || !interactive() || event.isComposing || !supportedKey(event.key)) return;
      event.preventDefault();
      const id = event.code || event.key;
      if (event.repeat && !keys.has(id)) return; // A resize/source switch already released this key.
      const key = keys.get(id) || event.key;
      if (!keys.has(id) && keys.size >= 64) { release(); return; }
      const alreadyHeld = [...keys.values()].includes(key);
      const repeat = keys.has(id);
      keys.set(id, key);
      if (repeat || !alreadyHeld) emit({ kind: 'key', key, down: true });
    });
    surface.addEventListener('keyup', event => {
      const id = event.code || event.key, key = keys.get(id);
      if (key === undefined) return;
      event.preventDefault(); keys.delete(id);
      if (![...keys.values()].includes(key)) emit({ kind: 'key', key, down: false });
    });
    surface.addEventListener('blur', () => release());
  }
  return { bind, release, get active() { return buttons !== 0 || keys.size !== 0; },
    heartbeat() { if (interactive() && (buttons || keys.size)) emit({ kind: 'keepalive' }); } };
}
export function supportedKey(key) {
  return typeof key === 'string' && (([...key].length === 1 && key.codePointAt(0) >= 32) ||
    /^(Backspace|Tab|Enter|Escape|Insert|Delete|Home|End|PageUp|PageDown|ArrowLeft|ArrowUp|ArrowRight|ArrowDown|Shift|Control|Alt|Meta|CapsLock|NumLock|ScrollLock|Pause|PrintScreen|F([1-9]|1[0-2]))$/.test(key));
}
