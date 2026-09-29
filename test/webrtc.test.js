import test from 'node:test';
import assert from 'node:assert/strict';
import { createRtcReceiver, createControlOutbox } from '../common/src/main/assets/web/app.js';

function fixture(createPeer, supportsCodec = () => true) {
  const sent = [], measurements = [], timers = new Map();
  let sequence = 0, clock = 0, ready = 0, stopped = 0;
  const video = { videoWidth: 1280, videoHeight: 720, readyState: 2, play: async () => {} };
  const pc = { connectionState: 'new', iceGatheringState: 'complete', localDescription: { sdp: 'answer' },
    setRemoteDescription: async () => {}, createAnswer: async () => ({ type: 'answer', sdp: 'a=setup:active\r\n' }),
    setLocalDescription: async d => { pc.appliedAnswer = d; },
    close() { this.closed = true; }, getStats: async () => new Map([['video', { type: 'inbound-rtp', kind: 'video', framesDecoded: 10 }]]),
  };
  const receiver = createRtcReceiver(video, { send: m => sent.push(m), supportsCodec, createPeer: createPeer || (() => pc),
    onStats: (fps, metrics) => measurements.push({ fps, metrics }),
    onReady: () => ready++, onStop: () => stopped++, now: () => clock,
    schedule(fn, delay) { const id = ++sequence; timers.set(id, { fn, delay }); return id; },
    cancel(id) { timers.delete(id); },
  });
  return { receiver, video, pc, sent, measurements, timers, get ready() { return ready; }, get stopped() { return stopped; },
    async tick(delay, at) { clock = at; const entry = [...timers].find(([, t]) => t.delay === delay); assert.ok(entry); timers.delete(entry[0]); await entry[1].fn(); },
  };
}

test('JPEG remains active until the browser actually decodes a video frame', async () => {
  const f = fixture();
  await f.receiver.receive({ type: 'rtc-offer', id: 1, sdp: 'offer' });
  assert.deepEqual(f.sent, [{ type: 'rtc-answer', id: 1, sdp: 'answer' }]);
  assert.equal(f.pc.appliedAnswer.sdp, 'a=setup:passive\r\n');
  assert.equal(f.receiver.active, false);
  f.pc.ontrack({ streams: [{}] }); await Promise.resolve();
  assert.equal(f.receiver.active, true); assert.equal(f.ready, 1);
  f.video.onloadeddata(); assert.equal(f.ready, 1);
  assert.equal(f.sent.at(-1).type, 'rtc-ready');
  f.pc.connectionState = 'disconnected'; f.pc.onconnectionstatechange();
  assert.equal(f.receiver.active, false); assert.equal(f.video.srcObject, null);
  assert.deepEqual(f.sent.at(-1), { type: 'rtc-fallback', id: 1 });
});

test('missing WebRTC and first-frame timeout both fall back without closing the control socket', async () => {
  const absent = fixture(() => { throw new Error('unsupported'); });
  await absent.receiver.receive({ type: 'rtc-offer', id: 2, sdp: 'offer' });
  assert.deepEqual(absent.sent, [{ type: 'rtc-fallback', id: 2 }]);
  const f = fixture(); await f.receiver.receive({ type: 'rtc-offer', id: 3, sdp: 'offer' });
  await f.tick(8000, 8000);
  assert.equal(f.pc.closed, true); assert.equal(f.stopped, 1);
});

test('late SDP and stale stop cannot affect a replacement connection', async () => {
  let resolve;
  const f = fixture(); f.pc.setRemoteDescription = () => new Promise(r => { resolve = r; });
  const pending = f.receiver.receive({ type: 'rtc-offer', id: 4, sdp: 'offer' });
  f.receiver.close(); resolve(); await pending;
  assert.equal(f.sent.length, 0);
  f.pc.setRemoteDescription = async () => {};
  await f.receiver.receive({ type: 'rtc-offer', id: 5, sdp: 'offer' });
  await f.receiver.receive({ type: 'rtc-stop', id: 4 });
  assert.equal(f.sent.at(-1).id, 5); assert.equal(f.stopped, 0);
});

test('static scenes remain connected, but incoming undecodable frames trigger fallback', async () => {
  const f = fixture(); await f.receiver.receive({ type: 'rtc-offer', id: 6, sdp: 'offer' });
  f.pc.ontrack({ streams: [{}] }); await Promise.resolve();
  await f.tick(1000, 1000);
  await f.tick(1000, 10000); assert.equal(f.receiver.active, true);
  f.receiver.progress(100);
  await f.tick(1000, 11000); assert.equal(f.receiver.active, false);
});

test('SDP backpressure retains signaling order and prioritizes the JPEG acknowledgement', () => {
  const messages = [], tasks = [];
  const socket = { readyState: 1, bufferedAmount: 9000, send: data => messages.push(JSON.parse(data)), close() {} };
  const outbox = createControlOutbox(socket, { schedule: fn => tasks.push(fn), cancel() {} });
  outbox.send({ type: 'rtc-answer', id: 1, sdp: 'answer' }); outbox.send({ type: 'rtc-ready', id: 1 }); outbox.send({ type: 'ack' });
  socket.bufferedAmount = 0; tasks.shift()();
  assert.deepEqual(messages.map(m => m.type), ['ack', 'rtc-answer', 'rtc-ready']);
});

test('HEVC unsupported by the receiver falls back before creating a peer', async () => {
  let created = false;
  const f = fixture(() => { created = true; }, codec => codec === 'H264');
  await f.receiver.receive({ type: 'rtc-offer', id: 10, codec: 'H265', sdp: 'offer' });
  assert.equal(created, false);
  assert.deepEqual(f.sent, [{ type: 'rtc-fallback', id: 10 }]);
  assert.equal(f.receiver.active, false);
});

test('HEVC negotiates its own codec and waits for decoded output; rejected media falls back', async () => {
  const f = fixture();
  await f.receiver.receive({ type: 'rtc-offer', id: 11, codec: 'H265', sdp: 'offer' });
  assert.equal(f.receiver.codec, 'H265');
  assert.equal(f.receiver.active, false);
  f.pc.ontrack({ streams: [{}] }); await Promise.resolve();
  assert.equal(f.receiver.active, true);
  f.pc.createAnswer = async () => ({ type: 'answer', sdp: 'm=video 0 UDP/TLS/RTP/SAVPF 96\r\n' });
  await f.receiver.receive({ type: 'rtc-offer', id: 12, codec: 'H265', sdp: 'offer' });
  assert.deepEqual(f.sent.at(-1), { type: 'rtc-fallback', id: 12 });
  assert.equal(f.receiver.active, false);
});


test('receiver reports interval rates and separate presentation, decode and jitter timings', async () => {
  const f = fixture(); let callback, canceled;
  f.video.requestVideoFrameCallback = fn => { callback = fn; return 7; };
  f.video.cancelVideoFrameCallback = id => { canceled = id; };
  let stat = { type: 'inbound-rtp', kind: 'video', framesReceived: 10, framesDecoded: 10, packetsReceived: 20,
    bytesReceived: 1000, framesDropped: 0, packetsLost: 0, nackCount: 0,
    totalDecodeTime: .1, jitterBufferDelay: .2, jitterBufferEmittedCount: 10, totalProcessingDelay: .3 };
  f.pc.getStats = async () => new Map([['video', stat], ['pair', { type: 'candidate-pair', nominated: true, state: 'succeeded', currentRoundTripTime: .015 }]]);
  await f.receiver.receive({ type: 'rtc-offer', id: 20, sdp: 'offer' });
  f.pc.ontrack({ streams: [{}] }); await Promise.resolve();
  callback(0, { presentedFrames: 2 });
  await f.tick(1000, 1000);
  assert.equal(f.measurements.at(-1).fps, null); // First cumulative snapshot is a baseline.
  stat = { ...stat, framesReceived: 40, framesDecoded: 40, packetsReceived: 110, bytesReceived: 126000,
    totalDecodeTime: .4, jitterBufferDelay: 1.1, jitterBufferEmittedCount: 40, totalProcessingDelay: 1.5 };
  callback(0, { presentedFrames: 30 });
  await f.tick(1000, 2000);
  const m = f.measurements.at(-1);
  assert.equal(m.fps, 28); assert.equal(m.metrics.decodedFps, 30); assert.equal(m.metrics.receivedFps, 30);
  assert.ok(Math.abs(m.metrics.decodeMs - 10) < .001);
  assert.ok(Math.abs(m.metrics.jitterBufferMs - 30) < .001);
  assert.equal(m.metrics.rttMs, 15);
  assert.equal(f.sent.at(-1).type, 'rtc-feedback'); assert.equal(f.sent.at(-1).decoded, 40);
  const old = callback; f.receiver.close(); assert.equal(canceled, 7);
  old(0, { presentedFrames: 100 }); // Stale callbacks must not restart observation.
  assert.equal(callback, old);
});

test('feedback is coalesced behind essential controls and cleared on close', () => {
  const sent = [], tasks = [];
  const socket = { readyState: 1, bufferedAmount: 9000, send: text => sent.push(JSON.parse(text)), close() {} };
  const outbox = createControlOutbox(socket, { schedule: fn => tasks.push(fn), cancel() {} });
  outbox.send({ type: 'rtc-feedback', id: 1, decoded: 10 });
  outbox.send({ type: 'rtc-feedback', id: 1, decoded: 20 });
  outbox.send({ type: 'rtc-ready', id: 1 }); outbox.send({ type: 'ack' });
  socket.bufferedAmount = 0; tasks.shift()();
  assert.deepEqual(sent.map(x => x.type), ['ack', 'rtc-ready', 'rtc-feedback']);
  assert.equal(sent.at(-1).decoded, 20);
  socket.bufferedAmount = 9000; outbox.send({ type: 'rtc-feedback', id: 1, decoded: 30 });
  outbox.close(); socket.bufferedAmount = 0; tasks.shift()();
  assert.equal(sent.length, 3);
});
