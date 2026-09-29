import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replaceAll('export function', 'function') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replaceAll('export function', 'function');
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture(status) {
  const elements = new Map(), timers = new Map(), sockets = [], requests = [];
  let nextTimer = 0;
  const element = id => {
    if (!elements.has(id)) elements.set(id, { hidden: false, value: '123456', handlers: {},
      set src(value) { this.source = value; if (this.onload) this.onload(); },
      classList: { toggle() {} }, removeAttribute() {}, setAttribute() {}, addEventListener(name, fn) { this.handlers[name] = fn; } });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; sockets.push(this); }
    close() {}
  }
  const context = { document: { getElementById: element, addEventListener() {} },
    window: { addEventListener() {} }, location: { host: 'server:8080' }, URL, Date, WebSocket: Socket,
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, clearInterval() {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (url.endsWith('/status')) return status();
      return { ok: true, json: async () => ({ id: 'request', secret: 'poll-secret', expiresIn: 120 }) };
    }
  };
  vm.runInNewContext(source, context);
  return { element, sockets, requests, poll: () => [...timers.values()].find(t => t.delay === 1000).fn() };
}

test('QR approval automatically connects with the issued token', async () => {
  const f = fixture(async () => ({ ok: true, json: async () => ({ state: 'approved', token: 'browser-token' }) }));
  await flush();
  assert.equal(f.element('pair-qr').hidden, false);
  await f.poll();
  assert.equal(f.sockets.length, 1);
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=browser-token');
  assert.equal(f.element('pair-qr').hidden, true);
  assert.equal(f.requests.some(r => r.url.endsWith('/cancel')), false);
});

test('late QR approval cannot replace a manual code connection', async () => {
  let resolve;
  const f = fixture(() => new Promise(done => { resolve = done; }));
  await flush();
  const polling = f.poll();
  f.element('connect-form').handlers.submit({ preventDefault() {} });
  resolve({ ok: true, json: async () => ({ state: 'approved', token: 'late-token' }) });
  await polling;
  assert.equal(f.sockets.length, 1);
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?code=123456');
  assert.equal(f.requests.some(r => r.url.endsWith('/cancel')), true);
});

test('expired QR is removed while manual pairing remains available', async () => {
  const f = fixture(async () => ({ ok: false, status: 404 }));
  await flush(); await f.poll();
  assert.equal(f.element('pair-qr').hidden, true);
  assert.match(f.element('qr-status').textContent, /已过期/);
  assert.equal(f.element('refresh-qr').disabled, false);
  f.element('connect-form').handlers.submit({ preventDefault() {} });
  assert.equal(f.sockets.length, 1);
});
