import test from 'node:test';
import assert from 'node:assert/strict';
import { createBrowserMediaSession } from '../common/src/main/assets/web/browser-media-session.js';
import { createControlOutbox } from '../common/src/main/assets/web/app.js';

function fixture(overrides = {}) {
  const handlers = new Map(), positions = [], sent = [], metadata = [];
  const session = { setActionHandler: (action, fn) => handlers.set(action, fn),
    setPositionState: value => { if (value?.playbackRate === 0) throw new Error('Zero rate'); positions.push(value); },
    set metadata(value) { metadata.push(value); }, playbackState: 'none' };
  let time = 0;
  const bridge = createBrowserMediaSession({ session, Metadata: class { constructor(value) { Object.assign(this, value); } },
    baseUrl: 'https://car.local:8443/', send: value => { sent.push(value); return true; }, now: () => time, ...overrides });
  bridge.open();
  const state = { type: 'now-playing', sessionId: 'one', revision: 1, connectionGeneration: 2, trackRevision: 3,
    connected: true, available: true, title: 'Song', artist: 'Artist', album: 'Album', status: 'playing',
    durationMs: 100000, positionMs: 12345, speed: 1, artworkUrl: '/now-playing/artwork?id=secret', canSeek: false };
  return { bridge, state, session, handlers, positions, sent, metadata, advance: ms => { time += ms; } };
}

test('phone snapshots publish metadata, cover and seconds; pause uses a nonzero rate and no seek controls', () => {
  const f = fixture(); f.bridge.receive(f.state);
  assert.equal(f.session.playbackState, 'playing');
  assert.equal(f.metadata.at(-1).title, 'Song');
  assert.equal(f.metadata.at(-1).artwork[0].src, 'https://car.local:8443/now-playing/artwork?id=secret');
  assert.deepEqual(f.positions.at(-1), { duration: 100, position: 12.345, playbackRate: 1 });
  const count = f.metadata.length;
  f.bridge.receive({ ...f.state, revision: 2, status: 'paused', positionMs: 13000, speed: 0 });
  assert.equal(f.session.playbackState, 'paused');
  assert.deepEqual(f.positions.at(-1), { duration: 100, position: 13, playbackRate: 1 });
  assert.equal(f.metadata.length, count, 'Progress changes must not republish metadata');
  for (const action of ['seekto', 'seekforward', 'seekbackward']) assert.equal(f.handlers.get(action), null);
});

test('controls use the current phone generation, wait for results and never optimistically alter playback', () => {
  const f = fixture(); f.bridge.receive(f.state);
  f.handlers.get('pause')();
  assert.deepEqual(f.sent[0], { type: 'media-command', requestId: 1, sessionId: 'one', connectionGeneration: 2, action: 'pause' });
  assert.equal(f.session.playbackState, 'playing');
  f.handlers.get('nexttrack')(); assert.equal(f.sent.length, 1);
  f.bridge.receive({ type: 'media-command-result', requestId: 1, result: 'sent' });
  assert.equal(f.session.playbackState, 'playing');
  f.handlers.get('nexttrack')(); assert.equal(f.sent.at(-1).action, 'nexttrack');
  f.advance(3001); f.handlers.get('previoustrack')(); assert.equal(f.sent.at(-1).action, 'previoustrack');
});

test('stale snapshots and retired handlers cannot restore or control a replacement phone', () => {
  const f = fixture(); f.bridge.receive(f.state);
  const oldPlay = f.handlers.get('play');
  f.bridge.receive({ ...f.state, sessionId: 'two', revision: 3, title: 'New phone' });
  oldPlay(); assert.equal(f.sent.length, 0);
  f.bridge.receive({ ...f.state, revision: 2 });
  assert.equal(f.metadata.at(-1).title, 'New phone');
  const currentPlay = f.handlers.get('play');
  f.bridge.close(); currentPlay();
  assert.equal(f.sent.length, 0); assert.equal(f.metadata.at(-1), null);
  assert.equal(f.session.playbackState, 'none'); assert.equal(f.positions.at(-1), undefined);
  for (const handler of f.handlers.values()) assert.equal(handler, null);
  f.bridge.receive({ ...f.state, revision: 9 }); assert.equal(f.metadata.at(-1), null);
  f.bridge.open(); f.bridge.receive(f.state); assert.equal(f.metadata.at(-1).title, 'Song');
  currentPlay(); assert.equal(f.sent.length, 0);
});

test('missing duration, track changes and disconnects clear stale position and artwork', () => {
  const f = fixture(); f.bridge.receive(f.state);
  f.bridge.receive({ ...f.state, revision: 2, trackRevision: 4, artworkUrl: null, durationMs: null, positionMs: null });
  assert.deepEqual(f.metadata.at(-1).artwork, []); assert.equal(f.positions.at(-1), undefined);
  f.bridge.receive({ ...f.state, revision: 3, positionMs: 999999, speed: 2 });
  assert.deepEqual(f.positions.at(-1), { duration: 100, position: 100, playbackRate: 2 });
  f.bridge.receive({ ...f.state, revision: 4, connected: false, available: false });
  assert.equal(f.metadata.at(-1), null); assert.equal(f.session.playbackState, 'none');
});

test('absent and partially implemented APIs cannot break the stream; cross-origin artwork is ignored', () => {
  for (const session of [null, {}, { setPositionState() { throw new Error('Unsupported'); }, setActionHandler() { throw new Error('Unsupported'); } }]) {
    const f = fixture({ session });
    assert.doesNotThrow(() => { f.bridge.receive(f.state); f.bridge.close(); });
  }
  const f = fixture(); f.bridge.receive({ ...f.state, artworkUrl: 'https://elsewhere.invalid/cover.jpg' });
  assert.deepEqual(f.metadata.at(-1).artwork, []);
  assert.equal(f.bridge.receive({ type: 'status' }), false);
});

test('media commands survive socket backpressure once, and disconnect discards queued commands', () => {
  const sent = [], scheduled = [];
  const socket = { readyState: 1, bufferedAmount: 9000, send: value => sent.push(JSON.parse(value)) };
  const outbox = createControlOutbox(socket, { schedule: fn => { scheduled.push(fn); return 1; }, cancel() {} });
  const f = fixture({ send: value => outbox.send(value) });
  f.bridge.receive(f.state); f.handlers.get('play')(); assert.equal(sent.length, 0);
  socket.bufferedAmount = 0; scheduled.shift()();
  assert.equal(sent.length, 1); assert.equal(sent[0].action, 'play');
  f.bridge.receive({ type: 'media-command-result', requestId: 1 });
  socket.bufferedAmount = 9000; f.handlers.get('pause')();
  outbox.close(); f.bridge.close(); socket.bufferedAmount = 0; scheduled.shift()();
  assert.equal(sent.length, 1);
});
