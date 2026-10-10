import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replaceAll('export function', 'function') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replaceAll('export function', 'function');
const flush = () => new Promise(resolve => setImmediate(resolve));
const savedDevice = { id: 'd'.repeat(32), secret: 's'.repeat(32), name: 'Chrome · Android' };
const ok = data => ({ ok: true, json: async () => data });
function fixture({ status = () => ok({ state: 'pending' }), stored = null, resume = () => ok({ token: 'resumed-token', name: savedDevice.name }), storageDisabled = false, search = '', tls = { available: true, port: 8443 }, href = 'http://server:8080/?code=123456#old' } = {}) {
  const elements = new Map(), timers = new Map(), sockets = [], requests = [], windowEvents = {};
  const preferences = new Map(stored ? [['wheelplay.browserDevice', JSON.stringify(stored)]] : []);
  let nextTimer = 0;
  const element = id => {
    if (!elements.has(id)) elements.set(id, { hidden: false, value: '123456', handlers: {}, checked: false,
      set src(value) { this.source = value; if (this.onload) this.onload(); },
      classList: { toggle() {} }, removeAttribute(name) { if (name === "href") delete this.href; }, setAttribute() {}, addEventListener(name, fn) { this.handlers[name] = fn; } });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; sockets.push(this); }
    close() {}
  }
  const context = { document: { getElementById: element, addEventListener() {} },
    window: { location: { search, pathname: '/', hash: '' }, history: { replaceState() {} },
      addEventListener(name, fn) { windowEvents[name] = fn; },
      localStorage: {
        getItem(key) { if (storageDisabled) throw new Error('denied'); return preferences.get(key) ?? null; },
        setItem(key, value) { if (storageDisabled) throw new Error('denied'); preferences.set(key, value); },
        removeItem(key) { preferences.delete(key); },
      },
    }, location: { host: 'server:8080', href }, URL, Date, WebSocket: Socket,
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, clearInterval() {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (url === '/tls.json') return ok(tls);
      if (url.endsWith('/status')) return status();
      if (url.endsWith('/resume')) return resume();
      if (url.endsWith('/remember')) return ok(savedDevice);
      if (url.endsWith('/code')) return ok({ token: 'code-token' });
      return ok({ id: 'request', secret: 'poll-secret', expiresIn: 120 });
    },
  };
  vm.runInNewContext(source, context);
  return { element, sockets, requests, preferences, windowEvents,
    submit: () => element('connect-form').handlers.submit({ preventDefault() {} }),
    poll: () => [...timers.values()].find(t => t.delay === 1000).fn() };
}

test('QR approval remembers the browser and waits for the explicit launch button', async () => {
  const f = fixture({ status: () => ok({ state: 'approved', token: 'browser-token' }) });
  await flush(); assert.equal(f.element('pair-qr').hidden, false);
  await f.poll();
  assert.equal(f.sockets.length, 0);
  assert.equal(f.element('remembered-device').hidden, false);
  assert.equal(f.element('code').required, false);
  assert.equal(f.element('connect-label').textContent, '启动显示');
  assert.deepEqual(JSON.parse(f.preferences.get('wheelplay.browserDevice')), savedDevice);
  f.element('hide-toolbar').checked = true; f.element('canvas-video').checked = true;
  f.submit(); await flush();
  assert.equal(f.sockets.length, 1);
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=resumed-token');
  assert.equal(f.element('hide-toolbar').checked, true);
  assert.equal(f.element('canvas-video').checked, true);
  assert.equal(f.requests.some(r => r.url.endsWith('/cancel')), false);
});

test('late QR approval cannot replace a manual code connection', async () => {
  let resolve;
  const f = fixture({ status: () => new Promise(done => { resolve = done; }) });
  await flush(); const polling = f.poll(); f.submit();
  resolve(ok({ state: 'approved', token: 'late-token' }));
  await polling; await flush();
  assert.equal(f.sockets.length, 1);
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=code-token');
  assert.equal(f.requests.some(r => r.url.endsWith('/cancel')), true);
  assert.equal(f.requests.find(r => r.url.endsWith('/remember')).options.headers['X-Pair-Token'], 'code-token');
});

test('expired QR is removed while manual pairing remains available', async () => {
  const f = fixture({ status: () => ({ ok: false, status: 404 }) });
  await flush(); await f.poll();
  assert.equal(f.element('pair-qr').hidden, true);
  assert.match(f.element('qr-status').textContent, /已过期/);
  assert.equal(f.element('refresh-qr').disabled, false);
  f.submit(); await flush(); assert.equal(f.sockets.length, 1);
});

test('reopening a remembered browser authenticates without QR or stream until launch', async () => {
  const f = fixture({ stored: savedDevice }); await flush();
  assert.deepEqual(f.requests.map(r => r.url), ['/pair/resume']);
  assert.equal(f.sockets.length, 0);
  assert.equal(f.element('code-entry').hidden, true);
  assert.equal(f.element('qr-pairing').hidden, true);
  assert.equal(f.element('connect').disabled, false);
  assert.equal(f.requests[0].options.headers['X-Device-Secret'], savedDevice.secret);
  f.submit(); await flush(); assert.equal(f.sockets.length, 1);
});

test('removed device clears saved credentials and offers QR or code pairing', async () => {
  const f = fixture({ stored: savedDevice, resume: () => ({ ok: false, status: 401 }) });
  await flush();
  assert.equal(f.preferences.has('wheelplay.browserDevice'), false);
  assert.equal(f.element('code').required, true);
  assert.equal(f.element('code').disabled, false);
  assert.equal(f.element('qr-pairing').hidden, false);
  assert.equal(f.sockets.length, 0);
  assert.match(f.element('message').textContent, /重新配对/);
  f.submit(); await flush(); assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=code-token');
});

test('temporary service failure preserves remembered credentials and launch retries', async () => {
  let available = false;
  const f = fixture({ stored: savedDevice, resume: () => {
    if (!available) throw new Error('offline');
    return ok({ token: 'recovered-token', name: savedDevice.name });
  } });
  await flush();
  assert.equal(f.preferences.has('wheelplay.browserDevice'), true);
  assert.equal(f.element('connect').disabled, false);
  assert.equal(f.requests.some(r => r.url.endsWith('/request')), false);
  available = true; f.submit(); await flush();
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=recovered-token');
});

test('quick browser link pairs but keeps launch options available until a click', async () => {
  const f = fixture({ search: '?code=123456' }); await flush();
  assert.equal(f.sockets.length, 0);
  assert.equal(f.element('connect-label').textContent, '启动显示');
  f.submit(); await flush(); assert.equal(f.sockets.length, 1);
});

test('pagehide and back navigation retain device memory and never automatically launch', async () => {
  const f = fixture({ stored: savedDevice }); await flush();
  f.windowEvents.pagehide();
  assert.equal(f.preferences.has('wheelplay.browserDevice'), true);
  assert.equal(f.requests.some(r => r.url.endsWith('/revoke')), false);
  f.windowEvents.pageshow({ persisted: true }); await flush();
  assert.equal(f.sockets.length, 0);
  assert.equal(f.element('connect-label').textContent, '启动显示');
});

test('storage-disabled browsers can still pair and launch without being enrolled', async () => {
  const f = fixture({ storageDisabled: true, status: () => ok({ state: 'approved', token: 'browser-token' }) });
  await flush(); await f.poll();
  assert.equal(f.requests.some(r => r.url.endsWith('/remember')), false);
  assert.match(f.element('device-status').textContent, /无法保存/);
  f.submit(); assert.equal(f.sockets.length, 1);
});

test('revocation between restore and launch forces fresh pairing instead of using stale token', async () => {
  let revoked = false;
  const f = fixture({ stored: savedDevice, resume: () => revoked ? { ok: false, status: 401 } : ok({ token: 'old-token', name: savedDevice.name }) });
  await flush(); revoked = true; f.submit(); await flush();
  assert.equal(f.sockets.length, 0);
  f.submit(); await flush();
  assert.equal(f.sockets[0].url, 'ws://server:8080/stream?token=code-token');
});

test('HTTPS setup uses the configured port and strips pairing data, including IPv6 hosts', async () => {
  for (const [href, expected] of [
    ['http://server:8080/?code=123456#old', 'https://server:9443/'],
    ['http://[fd00::1]:8080/?code=123456', 'https://[fd00::1]:9443/'],
  ]) {
    const f = fixture({ href, tls: { available: true, port: 9443, fingerprint: 'verified' } });
    f.element('secure-setup').open = true;
    await f.element('secure-setup').handlers.toggle();
    assert.equal(f.element('https-entry').href, expected);
    assert.equal(f.element('tls-fingerprint').textContent, 'verified');
  }
});

test('unavailable HTTPS or invalid port withdraws a previously displayed link', async () => {
  for (const tls of [{ available: false, port: 9443 }, { available: true, port: 0 }, { available: true, port: '9443' }]) {
    const f = fixture({ tls });
    f.element('https-entry').href = 'https://server:8443/';
    f.element('secure-setup').open = true;
    await f.element('secure-setup').handlers.toggle();
    assert.equal(f.element('https-entry').href, undefined);
  }
});

test('disabled HTTPS removes the link and explains the app setting', async () => {
  const f = fixture({ tls: { available: false, enabled: false, port: 8443 } });
  f.element('https-entry').href = 'https://server:8443/';
  f.element('secure-setup').open = true;
  await f.element('secure-setup').handlers.toggle();
  assert.equal(f.element('https-entry').href, undefined);
  assert.match(f.element('tls-fingerprint').textContent, /HTTPS 已关闭.*Web 监听/);
});
