import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { resourceStrings } from '../scripts/resource-strings.mjs';
const messages = resourceStrings('values-zh-rCN');

const source = readFileSync(new URL('../common/src/main/assets/web/text.js', import.meta.url), 'utf8').replace(/^import .*text\.js';$/m, '').replaceAll('export function', 'function') + '\n' + readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replace(/^import .*text\.js';$/m, '').replaceAll('export function', 'function') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replace(/^import .*text\.js';$/m, '').replaceAll('export function', 'function');
function fixture({ storedCanvas = null, storedMode = null, processorAvailable = true, storedIgnoreZoom = null, storageDisabled = false, canvasAvailable = true, supportsWebp = false,
  geometry = null, visual = null,
  fullscreen = 'supported', codeDenied = false, resume = () => ({ token: 'session-token', name: 'Chrome' }) } = {}) {
  const elements = new Map(), timers = new Map(), raf = new Map(), sockets = [], peers = [], draws = [];
  const preferences = new Map(storedCanvas === null ? [] : [['wheelplay.canvasVideo', storedCanvas]]);
  if (storedIgnoreZoom !== null) preferences.set('wheelplay.ignoreBrowserZoom', storedIgnoreZoom);
  const videoCallbacks = new Map(), createdVideos = [], processors = [];
  if (storedMode !== null) preferences.set('wheelplay.videoRenderer', storedMode);
  const documentEvents = {}, fullscreenCalls = [];
  const visualEvents = {};
  const visualViewport = visual ? { ...visual, addEventListener(type, fn) { visualEvents[type] = fn; } } : undefined;
  const mainRect = geometry ? { left: 0, top: geometry.top, width: geometry.width, height: geometry.height - geometry.top }
    : { left: 0, top: 0, width: 1280, height: 720 };
  let nextTimer = 0, nextFrame = 0, clockNow = 1000;
  const element = id => {
    const classes = new Set();
    if (!elements.has(id)) elements.set(id, { hidden: id === 'video-canvas', complete: true, value: '123456', handlers: {}, naturalWidth: 1280, naturalHeight: 720,
      checked: false, videoWidth: 1280, videoHeight: 720, readyState: 2, currentTime: 0,
      style: { removeProperty(name) { delete this[name]; } },
      parentElement: { getBoundingClientRect: () => mainRect },
      getContext: () => canvasAvailable ? { drawImage: (...args) => draws.push(args) } : null,
      play: async () => {},
      requestVideoFrameCallback(fn) { const frame = ++nextFrame; videoCallbacks.set(frame, fn); return frame; },
      cancelVideoFrameCallback(frame) { videoCallbacks.delete(frame); },
      classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name) },
      remove() { this.hidden = true; this.removed = true; },
      insertBefore(child) { child.removed = false; },
      removeAttribute() {}, setAttribute() {}, setPointerCapture() {},
      getBoundingClientRect() {
        if (id !== 'display' || !classes.has('ignore-browser-zoom')) return mainRect;
        const scale = Number(this.style.transform.match(/scale\((.*)\)/)[1]);
        return { left: parseFloat(this.style.left), top: parseFloat(this.style.top),
          width: parseFloat(this.style.width) * scale, height: parseFloat(this.style.height) * scale };
      },
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
  const document = { createElement(tag) { assert.equal(tag, 'video'); const video = element('video'); createdVideos.push(video); return video; }, getElementById: element, fullscreenElement: null, documentElement: {},
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
  const context = { document, window: { addEventListener() {}, visualViewport,
    localStorage: {
      getItem(key) { if (storageDisabled) throw new Error('denied'); return preferences.get(key) ?? null; },
      setItem(key, value) { if (storageDisabled) throw new Error('denied'); preferences.set(key, value); },
      removeItem(key) { preferences.delete(key); },
    },
  },
    location: { host: 'server:8080' }, URL: { createObjectURL: () => 'blob:frame', revokeObjectURL() {} }, Date, WebSocket: Socket,
    MediaStreamTrackProcessor: processorAvailable ? class {
      constructor(init) {
        this.init = init; this.pending = [];
        this.reader = {
          read: () => new Promise(resolve => this.pending.push(resolve)),
          cancel: () => { this.canceled = true; this.pending.splice(0).forEach(resolve => resolve({ done: true })); },
          releaseLock: () => { this.released = true; },
        };
        this.readable = { getReader: () => this.reader }; processors.push(this);
      }
    } : undefined,
    ArrayBuffer, DataView, Blob,
    RTCPeerConnection: Peer, performance: { now: () => clockNow },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay, at: clockNow + delay }); return id; },
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
  if (supportsWebp) context.Image = class {
    naturalWidth = 2; naturalHeight = 2;
    set src(value) { setImmediate(() => this.onload?.()); }
    removeAttribute() {}
  };
  context.wheelplayMessages = messages;
  vm.runInNewContext(source, context);
  return { element, sockets, timers, peers, draws, preferences, raf, videoCallbacks, document, fullscreenCalls, createdVideos, processors, visualViewport, visualEvents,
    reportViewport() {
      const [id, timer] = [...timers].find(([, t]) => t.delay === 180);
      timers.delete(id); timer.fn();
    },
    advance(ms) {
      clockNow += ms;
      for (;;) {
        const entry = [...timers].find(([, timer]) => timer.at <= clockNow);
        if (!entry) break;
        timers.delete(entry[0]); entry[1].fn();
      }
    },
    stats: () => context.window.wheelplayStats,
    select(mode) { element(`renderer-${mode}`).checked = true; element(`renderer-${mode}`).handlers.change(); },
    async decoded(width = 1280, height = 720) {
      const frame = { displayWidth: width, displayHeight: height, closes: 0, close() { this.closes++; } };
      processors.at(-1).pending.shift()({ value: frame, done: false });
      await new Promise(resolve => setImmediate(resolve)); return frame;
    },
    async connect() {
      element('connect-form').handlers.submit({ preventDefault() {} });
      await new Promise(resolve => setImmediate(resolve));
      const ws = sockets.at(-1); ws.readyState = 1; ws.onopen(); return ws;
    },
    frame(ws) { ws.onmessage({ data: {} }); [element('screen'), element('screen-next'), element('screen-third')].find(image => image.onload).onload(); },
    async offer(ws, id = 1, meta = {}) {
      ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id, sdp: 'offer', ...meta }) });
      await new Promise(resolve => setImmediate(resolve));
      peers.at(-1).ontrack({ track: { stop() {} }, streams: [{}] });
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
  old.bufferedAmount = 8192; old.onmessage({ data: {} });
  const lateImageLoad = [f.element('screen'), f.element('screen-next')].find(image => image.onload).onload;
  lateImageLoad();
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

test('renderer defaults, legacy migration, new preference precedence and storage errors', async () => {
  const defaults = fixture(); assert.equal(defaults.element('renderer-native').checked, true);
  const f = fixture({ storedCanvas: 'true' }); assert.equal(f.element('renderer-video-canvas').checked, true);
  f.select('track-canvas');
  assert.equal(f.preferences.get('wheelplay.videoRenderer'), 'track-canvas');
  assert.equal(f.preferences.get('wheelplay.canvasVideo'), 'true');
  assert.equal(f.element('renderer-video-canvas').checked, false);
  const preferred = fixture({ storedCanvas: 'true', storedMode: 'native' });
  assert.equal(preferred.element('renderer-native').checked, true);
  assert.equal(fixture({ storedCanvas: 'true', storedMode: 'invalid' }).element('renderer-video-canvas').checked, true);
  const denied = fixture({ storageDisabled: true }); denied.select('track-canvas');
  assert.equal((await denied.connect()).readyState, 1);
  assert.equal(denied.stats().presentation.selectedMode, 'track-canvas');
});

test('default WebRTC presents native video without allocating a Canvas', async () => {
  const f = fixture(), ws = await f.connect(); await f.offer(ws);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video').classList.contains('offscreen-video'), false);
  assert.equal(f.element('video-canvas').hidden, true); assert.equal(f.draws.length, 0);
  assert.equal(ws.sent.at(-1).type, 'rtc-ready');
});

test('a recoverable WebRTC disconnect preserves video and touch and reports recovery', async () => {
  const f = fixture(), ws = await f.connect(); await f.offer(ws);
  const peer = f.peers.at(-1);
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true }) });
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('waiting').hidden, true);
  assert.match(f.element('status').textContent, /WebRTC.*等待恢复/);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  assert.equal(ws.sent.some(m => m.type === 'rtc-fallback'), false);
  peer.connectionState = 'connected'; peer.onconnectionstatechange();
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true }) });
  assert.doesNotMatch(f.element('status').textContent, /等待恢复/);
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
  assert.match(f.element('status').textContent, /WebRTC \/ 兼容 Canvas/);
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
  assert.equal(ws.sent.at(-1).retryable, false);
  f.frame(ws); assert.equal(['screen', 'screen-next', 'screen-third'].some(id => !f.element(id).hidden), true);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  assert.equal(f.element('renderer-video-canvas').checked, true);
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
  assert.equal(f.sockets[1].url, 'ws://server:8080/stream?token=fresh-session&media=2&images=3');
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

test('ignore browser zoom defaults off and restores the saved choice', () => {
  const f = fixture();
  assert.equal(f.element('ignore-browser-zoom').checked, false);
  const option = f.element('ignore-browser-zoom');
  option.checked = true;
  option.handlers.change({ target: option });
  assert.equal(f.element('ignore-browser-zoom').checked, true);
  assert.equal(f.preferences.get('wheelplay.ignoreBrowserZoom'), 'true');
  const restored = fixture({ storedIgnoreZoom: 'true' });
  assert.equal(restored.element('ignore-browser-zoom').checked, true);
  const denied = fixture({ storageDisabled: true });
  const primary = denied.element('ignore-browser-zoom');
  primary.checked = true;
  assert.doesNotThrow(() => primary.handlers.change({ target: primary }));
});

test('a short split-screen viewport sends a valid canvas through the real zoom/style/report path', async () => {
  const f = fixture({ storedIgnoreZoom: 'true', geometry: { width: 960, height: 400, top: 64 },
    visual: { width: 960, height: 400, scale: 1, offsetLeft: 0, offsetTop: 0 } });
  const ws = await f.connect();
  f.reportViewport();
  assert.deepEqual(ws.sent.find(m => m.type === 'viewport'), { type: 'viewport', width: 960, height: 336 });
  Object.assign(f.visualViewport, { width: 640, height: 400 / 1.5, scale: 1.5 });
  f.visualEvents.resize(); f.reportViewport();
  const surface = f.element('display');
  assert.equal(surface.classList.contains('ignore-browser-zoom'), true);
  assert.equal(surface.style.transform, `scale(${1 / 1.5})`);
  const report = ws.sent.filter(m => m.type === 'viewport').at(-1);
  assert.deepEqual(report, { type: 'viewport', width: 1010, height: 320 });
  assert.ok(Math.abs(report.width / report.height - 960 / 304) < 0.01);
  const count = ws.sent.length;
  f.visualEvents.resize(); f.reportViewport();
  assert.equal(ws.sent.length, count, 'unchanged geometry must not renegotiate');
  const option = f.element('ignore-browser-zoom'); option.checked = false; option.handlers.change();
  f.reportViewport();
  assert.equal(surface.classList.contains('ignore-browser-zoom'), false);
  assert.equal(surface.style.transform, undefined);
  assert.deepEqual(ws.sent.filter(m => m.type === 'viewport').at(-1), { type: 'viewport', width: 960, height: 336 });
});

test('unsupported viewport shapes never reach Android as invalid control messages', async () => {
  const f = fixture({ storedIgnoreZoom: 'true', geometry: { width: 960, height: 400, top: 64 },
    visual: { width: 960, height: 400, scale: 1, offsetLeft: 0, offsetTop: 0 } });
  const ws = await f.connect(); f.reportViewport();
  const count = ws.sent.filter(m => m.type === 'viewport').length;
  Object.assign(f.visualViewport, { height: 100 });
  f.visualEvents.resize(); f.reportViewport();
  assert.equal(ws.sent.filter(m => m.type === 'viewport').length, count);
  assert.equal(ws.readyState, 1);
});

function videoPacket(videoEpoch, { sessionId = 7, width = 1280, height = 720, protocol = 2, sequence = videoEpoch, format = 1 } = {}) {
  const offset = protocol === 3 ? 40 : 36;
  const packet = new ArrayBuffer(offset + 3), header = new DataView(packet);
  header.setUint32(0, protocol === 3 ? 0x33565057 : 0x32565057, true); header.setFloat64(4, sessionId, true);
  header.setFloat64(12, videoEpoch, true); header.setFloat64(20, sequence, true);
  header.setUint32(28, width, true); header.setUint32(32, height, true);
  if (protocol === 3) header.setUint32(36, format, true);
  new Uint8Array(packet, offset).set([0xff, 0xd8, 0xff]); return packet;
}
function mediaStatus(ws, extra = {}) {
  ws.onmessage({ data: JSON.stringify({ type: 'status', mediaProtocol: 2, sessionId: 7, videoEpoch: 1,
    width: 1280, height: 720, streaming: true, sourceState: 'ready', inputReady: true, ...extra }) });
}
function finishJpeg(f, width = 1280, height = 720) {
  const image = [f.element('screen'), f.element('screen-next'), f.element('screen-third')].find(image => image.onload);
  image.naturalWidth = width; image.naturalHeight = height; image.onload(); return image;
}

test('v3 keeps two decodes alive, ACKs their identities, and never flashes or commits a late older decode', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws, { mediaProtocol: 3 });
  const packet = sequence => videoPacket(1, { protocol: 3, sequence });
  ws.onmessage({ data: packet(1) }); const front = finishJpeg(f);
  ws.onmessage({ data: packet(2) }); const second = f.element('screen-next'), late = second.onload;
  ws.onmessage({ data: packet(3) }); const third = f.element('screen-third');
  assert.equal(front.hidden, false); assert.ok(second.onload); assert.ok(third.onload);
  third.onload(); late(); assert.equal(third.hidden, false);
  assert.equal(f.element('waiting').hidden, true);
  assert.deepEqual(ws.sent.filter(m => m.type === 'ack').map(m => m.sequence).sort(), [1, 2, 3]);
  assert.ok(ws.sent.filter(m => m.type === 'ack').every(m => m.sessionId === 7 && m.videoEpoch === 1));
  ws.onmessage({ data: videoPacket(0, { protocol: 3, sequence: 4 }) });
  assert.equal(ws.sent.at(-1).sequence, 4); assert.equal(third.hidden, false);
});

test('WebP is advertised only after a real probe; decode failure requests JPEG while preserving the presented image', async () => {
  const f = fixture({ supportsWebp: true }), ws = await f.connect();
  await new Promise(resolve => setImmediate(resolve)); mediaStatus(ws, { mediaProtocol: 3 });
  assert.deepEqual(ws.sent.find(m => m.type === 'image-format'), { type: 'image-format', format: 'webp' });
  ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 1 }) }); const front = finishJpeg(f);
  ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 2, format: 2 }) });
  f.element('screen-next').onerror();
  assert.equal(front.hidden, false); assert.equal(f.element('waiting').hidden, true);
  assert.deepEqual(ws.sent.at(-1), { type: 'image-format', format: 'jpeg' });
  ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 3 }) }); finishJpeg(f);
  assert.equal(f.element('waiting').hidden, true);
});

test('RTC takeover retires both v3 decodes and late callbacks cannot send duplicate ACKs', async () => {
  for (const storedCanvas of [null, 'true']) {
    const f = fixture({ storedCanvas }), ws = await f.connect(); mediaStatus(ws, { mediaProtocol: 3 });
    ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 1 }) }); finishJpeg(f);
    ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 2 }) });
    ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 3 }) });
    const late = [f.element('screen-next').onload, f.element('screen-third').onload];
    await f.offer(ws, 1, { sessionId: 7, videoEpoch: 1 });
    assert.deepEqual(ws.sent.filter(m => m.type === 'ack').map(m => m.sequence).sort(), [1, 2, 3]);
    late.forEach(fn => fn());
    assert.equal(ws.sent.filter(m => m.type === 'ack').length, 3);
    assert.equal(['screen', 'screen-next', 'screen-third'].every(id => f.element(id).hidden), true);
    assert.equal(f.element(storedCanvas ? 'video-canvas' : 'video').hidden, false);
  }
});

test('automatic RTC retry preserves JPEG and touch until the new video frame is ready', async () => {
  for (const storedCanvas of [null, 'true']) {
    const f = fixture({ storedCanvas }), ws = await f.connect(); mediaStatus(ws);
    await f.offer(ws, 1, { sessionId: 7, videoEpoch: 1 });
    const first = f.peers.at(-1); first.connectionState = 'failed'; first.onconnectionstatechange();
    ws.onmessage({ data: videoPacket(1) }); const picture = finishJpeg(f);
    mediaStatus(ws, { rtcAvailable: false, rtcRetryAfterMs: 5000 });
    assert.equal(f.element('waiting').hidden, true);
    f.advance(5000);
    const startCount = () => ws.sent.filter(message => message.type === 'rtc-start').length;
    const before = startCount();
    mediaStatus(ws, { rtcAvailable: true, rtcRetryAfterMs: 0 });
    mediaStatus(ws, { rtcAvailable: true, rtcRetryAfterMs: 0 });
    assert.equal(startCount(), before + 1, 'one request for an advertised retry window');
    ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 2, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(picture.hidden, false, 'negotiation must keep JPEG visible');
    assert.equal(f.element('waiting').hidden, true);
    f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
    assert.equal(ws.sent.filter(message => message.type === 'touch').at(-1).contacts[0].down, false);
    ws.onmessage({ data: videoPacket(1) }); finishJpeg(f);
    const second = f.peers.at(-1);
    second.ontrack({ track: { stop() {} }, streams: [{}] }); await new Promise(resolve => setImmediate(resolve));
    assert.equal([f.element('screen'), f.element('screen-next')].every(image => image.hidden), true);
    assert.equal(f.element(storedCanvas ? 'video-canvas' : 'video').hidden, false);
    assert.equal(f.element('waiting').hidden, true);
    const starts = startCount();
    mediaStatus(ws, { rtcAvailable: false }); mediaStatus(ws, { rtcAvailable: true });
    assert.equal(startCount(), starts, 'an active receiver never requests a duplicate probe');
  }
});

test('a transient Canvas draw error permits a later retry to restore video', async () => {
  const f = fixture({ storedCanvas: 'true' }), ws = await f.connect(); mediaStatus(ws);
  const canvas = f.element('video-canvas'), getContext = canvas.getContext;
  canvas.getContext = () => ({ drawImage() { throw new Error('temporary surface loss'); } });
  await f.offer(ws, 1, { sessionId: 7, videoEpoch: 1 });
  assert.equal(ws.sent.at(-1).type, 'rtc-fallback');
  assert.notEqual(ws.sent.at(-1).retryable, false);
  ws.onmessage({ data: videoPacket(1) }); const picture = finishJpeg(f);
  mediaStatus(ws, { rtcAvailable: false, rtcRetryAfterMs: 5000 }); f.advance(5000);
  canvas.getContext = getContext;
  mediaStatus(ws, { rtcAvailable: true });
  await f.offer(ws, 2, { sessionId: 7, videoEpoch: 1 });
  assert.equal(picture.hidden, true); assert.equal(canvas.hidden, false);
});

test('failed retry and stale peer callbacks do not disturb the current JPEG or a reconnected session', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) }); const picture = finishJpeg(f);
  mediaStatus(ws, { rtcAvailable: true });
  ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 1, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
  await new Promise(resolve => setImmediate(resolve));
  const peer = f.peers.at(-1), lateTrack = peer.ontrack;
  peer.connectionState = 'failed'; peer.onconnectionstatechange();
  assert.equal(picture.hidden, false); assert.equal(f.element('waiting').hidden, true);
  mediaStatus(ws, { rtcAvailable: false, rtcRetryAfterMs: 10000 });
  ws.close();
  const current = await f.connect(); mediaStatus(current, { videoEpoch: 2 });
  current.onmessage({ data: videoPacket(2) }); finishJpeg(f);
  const before = current.sent.filter(message => message.type.startsWith('rtc-')).length;
  lateTrack({ streams: [{}] }); f.advance(30000);
  assert.equal(current.sent.filter(message => message.type.startsWith('rtc-')).length, before,
    'old RTC callbacks and deadlines cannot send on a new socket');
  assert.equal(f.element('waiting').hidden, true);
});

test('a same-version RTC offer keeps the pending JPEG available if negotiation fails', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) });
  ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 1, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
  await new Promise(resolve => setImmediate(resolve));
  finishJpeg(f);
  const peer = f.peers.at(-1); peer.connectionState = 'failed'; peer.onconnectionstatechange();
  assert.equal(f.element('screen').hidden, false);
  assert.equal(f.element('waiting').hidden, true);
  assert.equal(f.element('touch-surface').classList.contains('stale'), false);
});

test('a source gap releases touch, keeps the picture, and expires with no more status messages', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) }); finishJpeg(f);
  f.pointer('pointerdown', 100, 'touch-surface');
  mediaStatus(ws, { sourceState: 'recovering', streaming: false, inputReady: false });
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').at(-1).contacts, []);
  assert.equal(f.element('waiting').hidden, true);
  assert.equal(f.element('media-message').hidden, false);
  assert.match(f.element('media-message').textContent, /触控已暂停/);
  const before = ws.sent.filter(m => m.type === 'touch').length;
  f.pointer('pointerdown', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, before);
  f.advance(1999); assert.equal(f.element('waiting').hidden, true);
  f.advance(1); assert.equal(f.element('waiting').hidden, false);
  assert.equal(ws.readyState, 1);
  mediaStatus(ws); assert.equal(f.element('waiting').hidden, true);
});

test('status during JPEG decoding leaves the old image and its input geometry intact', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) }); const first = finishJpeg(f);
  ws.onmessage({ data: videoPacket(1) });
  const back = f.element('screen-next'); back.naturalWidth = 0;
  mediaStatus(ws);
  assert.equal(first.hidden, false); assert.equal(back.hidden, true);
  assert.equal(f.element('waiting').hidden, true);
  f.pointer('pointerdown', 100, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').at(-1), {
    type: 'touch', sessionId: 7, videoEpoch: 1, contacts: [{ id: 0, x: 100 / 1280, y: 100 / 720, down: true }],
  });
});

test('same-size rebuild cancels an old decode and ACKs it without accepting stale callbacks', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) }); finishJpeg(f);
  ws.onmessage({ data: videoPacket(1) });
  const late = f.element('screen-next').onload;
  const ackCount = ws.sent.filter(m => m.type === 'ack').length;
  mediaStatus(ws, { videoEpoch: 2 });
  assert.equal(ws.sent.filter(m => m.type === 'ack').length, ackCount + 1);
  late();
  assert.equal(f.element('touch-surface').classList.contains('stale'), true);
  ws.onmessage({ data: videoPacket(1) });
  assert.equal(f.element('screen-next').onload, null);
  ws.onmessage({ data: videoPacket(2) }); finishJpeg(f);
  assert.equal(f.element('touch-surface').classList.contains('stale'), false);
});

test('a rotated JPEG arriving before status disables old touch and commits the new geometry', async () => {
  const f = fixture(), ws = await f.connect(); mediaStatus(ws);
  ws.onmessage({ data: videoPacket(1) }); finishJpeg(f);
  f.pointer('pointerdown', 100, 'touch-surface');
  ws.onmessage({ data: videoPacket(2, { width: 720, height: 1280 }) });
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').at(-1).contacts, []);
  finishJpeg(f, 720, 1280);
  assert.equal(f.element('touch-surface').classList.contains('stale'), true, 'input needs a ready status');
  mediaStatus(ws, { videoEpoch: 2, width: 720, height: 1280 });
  f.pointer('pointerdown', 640, 'touch-surface');
  const touch = ws.sent.filter(m => m.type === 'touch').at(-1);
  assert.equal(touch.videoEpoch, 2); assert.equal(touch.contacts[0].x, .5);
});

test('an RTC rebuild keeps a frozen picture and rejects the previous offer and frame callbacks', async () => {
  for (const storedCanvas of [null, 'true']) {
    const f = fixture({ storedCanvas }), ws = await f.connect(); mediaStatus(ws);
    ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 1, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
    await new Promise(resolve => setImmediate(resolve));
    f.peers.at(-1).ontrack({ track: { stop() {} }, streams: [{}] }); await new Promise(resolve => setImmediate(resolve));
    const oldCallbacks = [...f.videoCallbacks.values()];
    mediaStatus(ws, { videoEpoch: 2 });
    assert.equal(f.element('held-frame').hidden, false);
    assert.equal(f.element('waiting').hidden, true);
    assert.equal(f.element('touch-surface').classList.contains('stale'), true);
    oldCallbacks.forEach(fn => fn(100, { presentedFrames: 100, mediaTime: .1 }));
    const count = f.peers.length;
    ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 2, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
    assert.equal(f.peers.length, count);
    f.advance(2000); assert.equal(f.element('waiting').hidden, false);
    ws.onmessage({ data: videoPacket(2) }); finishJpeg(f);
    assert.equal(f.element('held-frame').hidden, true);
    assert.equal(f.element('waiting').hidden, true);
  }
});

test('stopped media immediately covers an old image and a reconnected session needs a fresh frame', async () => {
  const f = fixture(), old = await f.connect(); mediaStatus(old);
  old.onmessage({ data: videoPacket(1) }); finishJpeg(f);
  mediaStatus(old, { videoEpoch: 2, sourceState: 'stopped', streaming: false, inputReady: false });
  assert.equal(f.element('waiting').hidden, false);
  old.close(); const current = await f.connect(); mediaStatus(current, { videoEpoch: 3 });
  f.advance(2000); assert.equal(f.element('waiting').hidden, false);
  current.onmessage({ data: videoPacket(3) }); finishJpeg(f);
  assert.equal(f.element('waiting').hidden, true);
});


test('Direct Canvas waits for drawing and never creates Video through recovery, fallback and reconnection', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect();
  f.frame(ws);
  ws.onmessage({ data: {} }); const lateJpeg = f.element('screen-next').onload;
  await f.offer(ws);
  assert.equal(ws.sent.some(m => m.type === 'rtc-ready'), false);
  assert.equal(f.element('screen').hidden, false);
  const frame = await f.decoded(); assert.equal(frame.closes, 1);
  assert.equal(ws.sent.filter(m => m.type === 'rtc-ready').length, 1);
  lateJpeg(); assert.equal(f.element('screen').hidden, true);
  assert.equal(f.element('video-canvas').hidden, false);
  assert.equal(f.processors[0].init.maxBufferSize, 1);
  const peer = f.peers.at(-1);
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  assert.equal(f.element('video-canvas').hidden, false);
  peer.connectionState = 'connected'; peer.onconnectionstatechange();
  f.pointer('pointerdown', 100, 'touch-surface');
  f.processors.at(-1).pending.shift()({ done: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ws.readyState, 1);
  assert.equal(f.stats().presentation.fallbackReason, 'track-ended');
  assert.equal(f.element('video-canvas').width, 0);
  assert.equal(ws.sent.filter(m => m.type === 'touch').at(-1).contacts.length, 0);
  f.frame(ws); assert.equal(['screen', 'screen-next', 'screen-third'].some(id => !f.element(id).hidden), true);
  ws.close();
  const next = await f.connect(); await f.offer(next, 2); await f.decoded();
  assert.equal(f.createdVideos.length, 0);
  assert.equal(f.stats().presentation.activeMode, 'track-canvas');
  assert.equal(f.processors[0].released, true);
});

test('unsupported Direct Canvas skips rtc-start and handles an unexpected offer without Video', async () => {
  const f = fixture({ storedMode: 'track-canvas', processorAvailable: false }), ws = await f.connect();
  assert.equal(f.element('renderer-hint').hidden, false);
  for (let i = 0; i < 3; i++) ws.onmessage({ data: JSON.stringify({ type: 'status', streaming: true, rtcAvailable: true }) });
  assert.equal(ws.sent.some(m => m.type === 'rtc-start'), false);
  f.frame(ws);
  assert.match(f.element('status').textContent, /直接 Canvas 不可用/);
  ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 9, sdp: 'offer' }) });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ws.sent.filter(m => m.type === 'rtc-fallback').length, 1);
  assert.equal(f.createdVideos.length, 0);
  assert.equal(f.preferences.get('wheelplay.videoRenderer'), 'track-canvas');
});

test('Direct Canvas resize controls touch mapping even after server status reports landscape', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect(); await f.offer(ws);
  await f.decoded(720, 1280);
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true }) });
  f.pointer('pointerdown', 100, 'touch-surface');
  assert.equal(ws.sent.some(m => m.type === 'touch'), false, 'Pillarbox is not touchable');
  f.pointer('pointerdown', 640, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').at(-1).contacts[0].x, .5);
  assert.equal(f.element('video-canvas').width, 720);
  assert.equal(f.element('video-canvas').height, 1280);
  f.pointer('pointerup', 640, 'touch-surface');
  await f.decoded(1280, 720); f.pointer('pointerdown', 100, 'touch-surface');
  assert.ok(ws.sent.filter(m => m.type === 'touch').at(-1).contacts[0].x < .1);
});

test('Direct Canvas drawing errors use JPEG and do not change browser audio or the connection', async () => {
  const f = fixture({ storedMode: 'track-canvas', canvasAvailable: false }), ws = await f.connect(); await f.offer(ws);
  assert.equal(ws.readyState, 1); assert.equal(f.createdVideos.length, 0);
  assert.equal(f.stats().presentation.fallbackReason, 'renderer-error');
  assert.equal(f.stats().presentation.activeMode, 'waiting');
  assert.equal(f.element('renderer-track-canvas').checked, true);
  f.pointer('pointerdown'); assert.equal(ws.sent.some(m => m.type === 'touch'), false);
  f.frame(ws); f.pointer('pointerdown'); f.pointer('pointerup');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
});

test('Direct Canvas failures freeze the last frame until the fallback image is decoded', async () => {
  for (const failure of ['draw', 'end']) {
    const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect();
    const canvas = f.element('video-canvas'), held = f.element('held-frame');
    let failDraw = false, copied = false;
    canvas.getContext = () => ({ drawImage() { if (failDraw) throw new Error('draw failed'); } });
    held.getContext = () => ({ drawImage(source) {
      assert.equal(source, canvas); assert.equal(source.hidden, false);
      assert.equal(source.width, 1280); assert.equal(source.height, 720);
      copied = true;
    } });
    await f.offer(ws); await f.decoded();
    f.pointer('pointerdown', 100, 'touch-surface');
    if (failure === 'draw') { failDraw = true; await f.decoded(); }
    else {
      f.processors.at(-1).pending.shift()({ done: true });
      await new Promise(resolve => setImmediate(resolve));
    }
    assert.equal(copied, true); assert.equal(held.hidden, false);
    assert.equal(canvas.width, 0); assert.equal(f.element('waiting').hidden, true);
    assert.deepEqual(ws.sent.filter(m => m.type === 'touch').at(-1).contacts, []);
    assert.equal(f.processors[0].canceled, true); assert.equal(f.processors[0].released, true);
    assert.equal(ws.sent.filter(m => m.type === 'rtc-fallback').length, 1);
    ws.onmessage({ data: {} });
    assert.equal(held.hidden, false, 'Keep the snapshot while the fallback image is loading');
    [f.element('screen'), f.element('screen-next'), f.element('screen-third')].find(image => image.onload).onload();
    assert.equal(held.hidden, true); assert.equal(held.width, 0);
    assert.equal(f.createdVideos.length, 0);
  }
});

test('an automatic retry keeps the launch mode until the user starts a new display session', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect();
  f.select('native'); ws.close();
  const retry = [...f.timers.values()].find(t => t.delay === 1000);
  await retry.fn();
  const replacement = f.sockets.at(-1); replacement.readyState = 1; replacement.onopen();
  assert.equal(f.stats().presentation.selectedMode, 'track-canvas');
  await f.offer(replacement); await f.decoded();
  assert.equal(f.createdVideos.length, 0);
  f.element('disconnect').handlers.click();
  const next = await f.connect(); await f.offer(next);
  assert.equal(f.stats().presentation.selectedMode, 'native');
  assert.equal(f.createdVideos.length, 1);
});

test('late JPEG errors cannot close a live Canvas stream or a replacement connection', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect();
  ws.onmessage({ data: {} }); const oldError = f.element('screen').onerror;
  await f.offer(ws); await f.decoded();
  oldError(); assert.equal(ws.readyState, 1);
  ws.close(); const replacement = await f.connect();
  oldError(); assert.equal(replacement.readyState, 1);
  assert.equal(f.createdVideos.length, 0);
});

test('a replaced JPEG load cannot ACK or display an obsolete image in the same session', async () => {
  const f = fixture(), ws = await f.connect();
  ws.onmessage({ data: {} }); const oldLoad = f.element('screen').onload;
  ws.onmessage({ data: {} });
  f.element('screen-next').onload();
  const before = ws.sent.length; oldLoad(); assert.equal(ws.sent.length, before);
  assert.equal(f.element('screen-next').hidden, false);
  assert.equal(f.element('screen').hidden, true);
});

test('Direct Canvas preserves versioned media isolation and input readiness across an RTC rebuild', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect(); mediaStatus(ws);
  await f.offer(ws, 1, { sessionId: 7, videoEpoch: 1 }); await f.decoded();
  f.pointer('pointerdown', 100, 'touch-surface');
  mediaStatus(ws, { videoEpoch: 2, sourceState: 'recovering', inputReady: false });
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').at(-1).contacts, []);
  assert.equal(f.element('held-frame').hidden, false);
  assert.equal(f.element('touch-surface').classList.contains('stale'), true);
  const count = f.peers.length;
  ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id: 2, sdp: 'offer', sessionId: 7, videoEpoch: 1 }) });
  assert.equal(f.peers.length, count);
  mediaStatus(ws, { videoEpoch: 2 });
  await f.offer(ws, 3, { sessionId: 7, videoEpoch: 2 }); await f.decoded(720, 1280);
  assert.equal(f.element('held-frame').hidden, true);
  assert.equal(f.element('touch-surface').classList.contains('stale'), false);
  f.pointer('pointerdown', 640, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').at(-1).videoEpoch, 2);
  assert.equal(f.createdVideos.length, 0);
});

test('Direct Canvas retries over v3 WebP without Video and only switches after the first new draw', async () => {
  const f = fixture({ storedMode: 'track-canvas' }), ws = await f.connect(); mediaStatus(ws);
  await f.offer(ws, 1, { sessionId: 7, videoEpoch: 1 }); await f.decoded();
  f.peers.at(-1).connectionState = 'failed'; f.peers.at(-1).onconnectionstatechange();
  ws.onmessage({ data: videoPacket(1, { protocol: 3, sequence: 17, format: 2 }) }); const image = finishJpeg(f);
  assert.match(f.element('status').textContent, /WebP/);
  assert.deepEqual(ws.sent.filter(m => m.type === 'ack').at(-1), { type: 'ack', sessionId: 7, videoEpoch: 1, sequence: 17 });
  mediaStatus(ws, { rtcAvailable: false }); mediaStatus(ws, { rtcAvailable: true });
  await f.offer(ws, 2, { sessionId: 7, videoEpoch: 1 });
  assert.equal(image.hidden, false);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  await f.decoded();
  assert.equal(image.hidden, true);
  assert.equal(f.stats().presentation.activeMode, 'track-canvas');
  assert.equal(f.stats().presentation.fallbackReason, null);
  assert.equal(f.createdVideos.length, 0);
});
