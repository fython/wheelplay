import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replaceAll('export function', 'function') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replaceAll('export function', 'function');
function fixture({ storedCanvas = null, storageDisabled = false, canvasAvailable = true,
  fullscreen = 'supported', codeDenied = false, resume = () => ({ token: 'session-token', name: 'Chrome' }) } = {}) {
  const elements = new Map(), timers = new Map(), raf = new Map(), sockets = [], peers = [], draws = [];
  const preferences = new Map(storedCanvas === null ? [] : [['wheelplay.canvasVideo', storedCanvas]]);
  const videoCallbacks = new Map();
  const documentEvents = {}, fullscreenCalls = [];
  let nextTimer = 0, nextFrame = 0;
  const element = id => {
    const classes = new Set();
    if (!elements.has(id)) elements.set(id, { hidden: false, value: '123456', handlers: {}, naturalWidth: 1280, naturalHeight: 720,
      checked: false, videoWidth: 1280, videoHeight: 720, readyState: 2, currentTime: 0,
      getContext: () => canvasAvailable ? { drawImage: (...args) => draws.push(args) } : null,
      play: async () => {},
      requestVideoFrameCallback(fn) { const frame = ++nextFrame; videoCallbacks.set(frame, fn); return frame; },
      cancelVideoFrameCallback(frame) { videoCallbacks.delete(frame); },
      classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name) },
      removeAttribute() {}, setAttribute() {}, setPointerCapture() {},
      getBoundingClientRect: () => ({ left: 0, top: 0, width: 1280, height: 720 }),
      addEventListener(name, fn) { this.handlers[name] = fn; } });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; this.onclose(); }
  }
  class Peer {
    constructor() { this.iceGatheringState = 'complete'; this.localDescription = { sdp: 'answer' }; peers.push(this); }
    async setRemoteDescription() {}
    async createAnswer() { return { type: 'answer', sdp: 'a=setup:active\r\n' }; }
    async setLocalDescription() {}
    close() { this.closed = true; }
  }
  const document = { getElementById: element, fullscreenElement: null, documentElement: {},
    addEventListener(name, fn) { documentEvents[name] = fn; },
    exitFullscreen() {
      fullscreenCalls.push('exit'); this.fullscreenElement = null;
      documentEvents.fullscreenchange(); return Promise.resolve();
    },
  };
  const enterFullscreen = () => { document.fullscreenElement = document.documentElement; documentEvents.fullscreenchange(); };
  if (fullscreen !== 'unsupported') document.documentElement.requestFullscreen = () => {
    fullscreenCalls.push('request');
    if (typeof fullscreen === 'function') return fullscreen(enterFullscreen);
    if (fullscreen === 'denied') return Promise.reject(new Error('fullscreen denied'));
    enterFullscreen(); return Promise.resolve();
  };
  const context = { document, window: { addEventListener() {},
    localStorage: {
      getItem(key) { if (storageDisabled) throw new Error('denied'); return preferences.get(key) ?? null; },
      setItem(key, value) { if (storageDisabled) throw new Error('denied'); preferences.set(key, value); },
      removeItem(key) { preferences.delete(key); },
    },
  },
    location: { host: 'server:8080' }, URL: { createObjectURL: () => 'blob:frame', revokeObjectURL() {} }, Date, WebSocket: Socket,
    RTCPeerConnection: Peer, performance: { now: () => 0 },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { const frame = ++nextFrame; raf.set(frame, fn); return frame; },
    cancelAnimationFrame(frame) { raf.delete(frame); },
    fetch: async url => {
      if (url === '/pair/code') return codeDenied ? { ok: false, status: 401 } : { ok: true, json: async () => ({ token: 'session-token' }) };
      if (url === '/pair/remember') return { ok: true, json: async () => ({ id: 'd'.repeat(32), secret: 's'.repeat(32), name: 'Chrome' }) };
      if (url === '/pair/resume') return { ok: true, json: async () => resume() };
      return new Promise(() => {});
    },
  };
  vm.runInNewContext(source, context);
  return { element, sockets, timers, peers, draws, preferences, raf, videoCallbacks, document, fullscreenCalls,
    async connect() {
      element('connect-form').handlers.submit({ preventDefault() {} });
      await new Promise(resolve => setImmediate(resolve));
      const ws = sockets.at(-1); ws.readyState = 1; ws.onopen(); return ws;
    },
    frame(ws) { ws.onmessage({ data: {} }); element('screen').onload(); },
    async offer(ws, id = 1) {
      ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id, sdp: 'offer' }) });
      await new Promise(resolve => setImmediate(resolve));
      peers.at(-1).ontrack({ streams: [{}] });
      await new Promise(resolve => setImmediate(resolve));
    },
    pointer(type, x = 100, surface = 'screen') { element(surface).handlers[type]({ pointerId: 1, pointerType: 'touch', clientX: x, clientY: 100, preventDefault() {} }); },
    flushMoves() { const callbacks = [...raf.values()]; raf.clear(); callbacks.forEach(fn => fn()); },
    retry() {
      const [id, timer] = [...timers].find(([, t]) => t.delay === 10);
      timers.delete(id); timer.fn();
    },
  };
}

test('the overlay forwards a complete drag independently of the native video element', async () => {
  const f = fixture(), ws = await f.connect(); f.frame(ws);
  f.pointer('pointerdown', 100, 'touch-surface');
  f.pointer('pointermove', 200, 'touch-surface'); f.flushMoves();
  f.pointer('pointerup', 200, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, true, false]);
});

test('the optional top bar is hidden only after connecting and restored on disconnect', async () => {
  const f = fixture();
  f.element('hide-toolbar').checked = true;
  const ws = await f.connect();
  assert.equal(f.element('topbar').hidden, true);
  f.element('disconnect').handlers.click();
  assert.equal(f.element('topbar').hidden, false);
});

test('normal launch leaves fullscreen and the top bar unchanged', async () => {
  const f = fixture(); await f.connect();
  assert.deepEqual(f.fullscreenCalls, []);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
});

test('immersive launch requests fullscreen in the submit gesture and hides the bar after connecting', async () => {
  const f = fixture(); f.element('fullscreen-on-start').checked = true;
  const connecting = f.connect();
  assert.deepEqual(f.fullscreenCalls, ['request']);
  assert.equal(f.sockets.length, 0, 'Fullscreen must be requested before asynchronous pairing finishes');
  assert.equal(f.element('topbar').hidden, false);
  await connecting;
  assert.equal(f.document.fullscreenElement, f.document.documentElement);
  assert.equal(f.element('topbar').hidden, true);
  await f.document.exitFullscreen();
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('display').hidden, false, 'Leaving fullscreen keeps the stream connected');
});

test('disconnect exits launch-owned fullscreen and restores the pairing interface', async () => {
  const f = fixture(); f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('pairing').hidden, false);
});

test('unavailable or rejected fullscreen falls back to hiding the bar without preventing launch', async () => {
  for (const fullscreen of ['unsupported', 'denied']) {
    const f = fixture({ fullscreen }); f.element('fullscreen-on-start').checked = true;
    await f.connect();
    assert.equal(f.document.fullscreenElement, null);
    assert.equal(f.element('topbar').hidden, true);
    assert.equal(f.element('display').hidden, false);
    f.element('disconnect').handlers.click();
    assert.equal(f.element('topbar').hidden, false);
    assert.equal(f.fullscreenCalls.includes('exit'), false);
  }
});

test('pairing failure exits launch-owned fullscreen without hiding the pairing interface', async () => {
  const f = fixture({ codeDenied: true }); f.element('fullscreen-on-start').checked = true;
  f.element('connect-form').handlers.submit({ preventDefault() {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.sockets.length, 0);
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('pairing').hidden, false);
  assert.equal(f.element('connect').disabled, false);
});

test('a fullscreen request that completes after disconnect is released', async () => {
  let complete;
  const f = fixture({ fullscreen: enter => new Promise(resolve => { complete = () => { enter(); resolve(); }; }) });
  f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  complete(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
});

test('disconnect leaves fullscreen that was already active before launch intact', async () => {
  const f = fixture(); f.document.fullscreenElement = f.document.documentElement;
  f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  assert.deepEqual(f.fullscreenCalls, []);
  assert.equal(f.document.fullscreenElement, f.document.documentElement);
});

test('actual image onload retries ACK and preserves down/up during browser congestion', async () => {
  const f = fixture(), ws = await f.connect();
  ws.bufferedAmount = 8192;
  f.frame(ws); f.pointer('pointerdown'); f.pointer('pointermove', 200); f.flushMoves(); f.pointer('pointerup', 200);
  assert.deepEqual(ws.sent.map(m => m.type), ['ping']);
  ws.bufferedAmount = 0; f.retry();
  assert.deepEqual(ws.sent.map(m => m.type), ['ping', 'ack', 'touch', 'touch', 'touch']);
  assert.deepEqual(ws.sent.slice(2).map(m => m.contacts[0].down), [true, true, false]);
});

test('disconnect drops pending ACK and old animation callbacks cannot send into a new gesture', async () => {
  const f = fixture(), old = await f.connect(); f.frame(old); f.pointer('pointerdown');
  f.pointer('pointermove', 200);
  old.bufferedAmount = 8192; f.frame(old);
  const lateImageLoad = f.element('screen').onload;
  old.close();
  const current = await f.connect(); f.frame(current); f.pointer('pointerdown', 300);
  const before = current.sent.length;
  lateImageLoad(); f.flushMoves();
  assert.equal(current.sent.length, before);
  assert.equal([...f.timers.values()].some(t => t.delay === 10), false);
  assert.equal(old.sent.filter(m => m.type === 'ack').length, 1);
});

test('pending movement is cancelled at up before another down', async () => {
  const f = fixture(), ws = await f.connect(); f.frame(ws);
  f.pointer('pointerdown'); f.pointer('pointermove', 200); f.pointer('pointerup'); f.pointer('pointerdown', 300);
  const before = ws.sent.length; f.flushMoves();
  assert.equal(ws.sent.length, before);
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, false, true]);
});

test('Canvas preference defaults off, persists per browser, and tolerates unavailable storage', async () => {
  const defaults = fixture(); assert.equal(defaults.element('canvas-video').checked, false);
  const f = fixture({ storedCanvas: 'true' }); assert.equal(f.element('canvas-video').checked, true);
  f.element('canvas-video').checked = false; f.element('canvas-video').handlers.change();
  assert.equal(f.preferences.get('wheelplay.canvasVideo'), 'false');
  const denied = fixture({ storageDisabled: true });
  denied.element('canvas-video').checked = true; denied.element('canvas-video').handlers.change();
  assert.equal((await denied.connect()).readyState, 1);
});

test('default WebRTC presents native video without allocating a Canvas', async () => {
  const f = fixture(), ws = await f.connect(); await f.offer(ws);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video').classList.contains('offscreen-video'), false);
  assert.equal(f.element('video-canvas').hidden, true); assert.equal(f.draws.length, 0);
  assert.equal(ws.sent.at(-1).type, 'rtc-ready');
});

test('Canvas WebRTC handles touch, late JPEG loads, and reconnection without leaving old callbacks', async () => {
  const f = fixture({ storedCanvas: 'true' }), ws = await f.connect();
  ws.onmessage({ data: {} }); const lateImage = f.element('screen').onload;
  await f.offer(ws);
  assert.equal(f.element('video-canvas').hidden, false);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video').classList.contains('offscreen-video'), true);
  lateImage(); assert.equal(f.element('screen').hidden, true);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, false]);
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true, rtcAvailable: true }) });
  assert.match(f.element('status').textContent, /WebRTC \/ Canvas/);
  const lateFrames = [...f.videoCallbacks.values()], lateRefresh = [...f.raf.values()];
  ws.close(); assert.equal(f.element('video-canvas').hidden, true);
  assert.equal(f.videoCallbacks.size, 0); assert.equal(f.raf.size, 0);
  const replacement = await f.connect(); await f.offer(replacement, 2); const count = f.draws.length;
  lateFrames.forEach(fn => fn(100, { presentedFrames: 100, mediaTime: .1 })); lateRefresh.forEach(fn => fn());
  assert.equal(f.draws.length, count); assert.equal(f.element('video-canvas').hidden, false);
});

test('Canvas failure falls back to JPEG while control and the selected preference remain available', async () => {
  const f = fixture({ storedCanvas: 'true', canvasAvailable: false }), ws = await f.connect(); await f.offer(ws);
  assert.equal(ws.readyState, 1);
  assert.equal(f.peers[0].closed, true);
  assert.equal(f.element('video-canvas').hidden, true);
  assert.equal(f.element('video').hidden, true);
  assert.equal(f.element('video').classList.contains('offscreen-video'), false);
  assert.equal(f.timers.size > 0 && [...f.timers.values()].some(t => t.delay === 1000), false);
  assert.equal(ws.sent.at(-1).type, 'rtc-fallback');
  f.frame(ws); assert.equal(f.element('screen').hidden, false);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  assert.equal(f.element('canvas-video').checked, true);
});


test('phone connection requires pairing and reports the server result', async () => {
  const f = fixture();
  f.element('phone-connect').handlers.click();
  assert.equal(f.sockets.length, 0);
  assert.match(f.element('phone-connect-message').textContent, /重新配对/);
  const ws = await f.connect();
  f.element('phone-connect').handlers.click();
  assert.equal(ws.sent.filter(m => m.type === 'phone-connect').length, 1);
  assert.equal(f.element('phone-connect').disabled, true);
  ws.onmessage({ data: JSON.stringify({ type: 'phone-connect-result', message: '请在 Android 上完成授权' }) });
  assert.equal(f.element('phone-connect').disabled, false);
  assert.equal(f.element('phone-connect-message').textContent, '请在 Android 上完成授权');
});

test('phone connection survives congestion and is sent once after recovery', async () => {
  const f = fixture(), ws = await f.connect();
  ws.bufferedAmount = 8193;
  f.element('phone-connect').handlers.click();
  assert.equal(ws.sent.some(m => m.type === 'phone-connect'), false);
  assert.equal(f.element('phone-connect').disabled, true);
  ws.bufferedAmount = 0;
  f.retry();
  assert.equal(ws.sent.filter(m => m.type === 'phone-connect').length, 1);
});


test('interrupted display resumes with fresh authentication after a server restart', async () => {
  const f = fixture({ resume: () => ({ token: 'fresh-session', name: 'Chrome' }) });
  const old = await f.connect(); old.close();
  const retry = [...f.timers.values()].find(t => t.delay === 1000);
  assert.ok(retry); await retry.fn();
  assert.equal(f.sockets.length, 2);
  assert.equal(f.sockets[1].url, 'ws://server:8080/stream?token=fresh-session');
});

test('revocation during an active display stops retries and requires fresh pairing', async () => {
  const f = fixture({ resume: () => { const error = new Error('revoked'); error.status = 401; throw error; } });
  const old = await f.connect(); old.close();
  await [...f.timers.values()].find(t => t.delay === 1000).fn();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.sockets.length, 1);
  assert.equal(f.element('code-entry').hidden, false);
  assert.equal(f.preferences.has('wheelplay.browserDevice'), false);
});
